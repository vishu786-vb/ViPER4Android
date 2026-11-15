package com.llsl.viper4android.loopback

import com.llsl.viper4android.utils.FileLogger

/**
 * Thread-safe, bounded PCM queue that bridges the AIDL binder delivery thread
 * (ILoopbackCaptureCallback.onPcm) and the ViPER processing thread.
 *
 * Mimics AudioRecord.read() semantics:
 *   - read() blocks until exactly [size] samples are available, returning
 *     immediately only when close() interrupts a blocked read (returns -1).
 *   - Bounded at MAX_BLOCKS (~0.5 s) to avoid unbounded memory growth if the
 *     DSP thread falls behind.
 *   - Object pool for ShortArray blocks to reduce GC pressure on the binder
 *     delivery thread (~50 allocations/sec at 20 ms blocks).
 */
class LoopbackPcmSource {

    private val TAG = "LoopbackPcmSource"
    private val lock = Object()
    private val queue = ArrayDeque<ShortArray>()
    private val pool  = ArrayDeque<ShortArray>()
    private var remainder: ShortArray? = null
    private var remainderOffset = 0
    private var closed = false
    private var droppedBlocks = 0L
    private var lastDropLogNanos = 0L

    val droppedBlockCount: Long
        get() = synchronized(lock) { droppedBlocks }

    // ── binder side (ILoopbackCaptureCallback.onPcm) ─────────────────────────

    /**
     * Accept a raw PCM16 LE byte[] from the AIDL callback and enqueue it as
     * a ShortArray.  Recycles blocks from the pool to avoid allocation.
     */
    fun offer(pcm: ByteArray) {
        require(pcm.size % 2 == 0) { "PCM16 block must have even length" }
        val block = synchronized(lock) {
            if (closed) return
            pool.removeFirstOrNull()?.takeIf { it.size == pcm.size / 2 }
                ?: ShortArray(pcm.size / 2)
        }
        // Little-endian PCM16 → Short
        for (i in block.indices) {
            block[i] = ((pcm[2 * i].toInt() and 0xFF) or
                        (pcm[2 * i + 1].toInt() shl 8)).toShort()
        }
        enqueue(block)
    }

    fun offer(pcm: ShortArray) = enqueue(pcm)

    private fun enqueue(block: ShortArray) {
        synchronized(lock) {
            if (closed) { recycle(block); return }
            queue.addLast(block)
            var dropped = 0
            while (queue.size > MAX_BLOCKS) { recycle(queue.removeFirst()); dropped++ }
            if (dropped > 0) {
                droppedBlocks += dropped
                val now = System.nanoTime()
                if (lastDropLogNanos == 0L || now - lastDropLogNanos >= DROP_LOG_INTERVAL_NS) {
                    lastDropLogNanos = now
                    FileLogger.w(TAG, "PCM queue overflow: dropped $dropped blocks " +
                        "($droppedBlocks total)")
                }
            }
            lock.notifyAll()
        }
    }

    // ── DSP processing thread side ────────────────────────────────────────────

    /**
     * AudioRecord-compatible blocking read.
     *
     * Normally returns exactly [size] samples.  Returns a short count only if
     * close() is called while partially filling a read.  Returns -1 if the
     * source is already closed and the queue is empty.
     */
    fun read(destination: ShortArray, offset: Int, size: Int): Int {
        synchronized(lock) {
            var written = 0
            while (written < size) {
                while (!closed && queue.isEmpty() && remainder == null) {
                    lock.wait()
                }
                if (closed && queue.isEmpty() && remainder == null) {
                    return if (written > 0) written else -1
                }

                // Drain the partial remainder first
                remainder?.let { block ->
                    val take = minOf(block.size - remainderOffset, size - written)
                    System.arraycopy(block, remainderOffset, destination, offset + written, take)
                    remainderOffset += take
                    written += take
                    if (remainderOffset == block.size) {
                        recycle(block); remainder = null; remainderOffset = 0
                    }
                    if (written == size) return written
                }

                if (queue.isEmpty()) continue   // wait for more data

                val block = queue.removeFirst()
                val take = minOf(block.size, size - written)
                System.arraycopy(block, 0, destination, offset + written, take)
                if (take < block.size) { remainder = block; remainderOffset = take }
                else recycle(block)
                written += take
            }
            return written
        }
    }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Reset for reuse: drain the queue, clear remainder, re-open source.
     * Blocked read() calls wake up and can receive data again.
     */
    fun clear() {
        synchronized(lock) {
            queue.forEach(::recycle); queue.clear()
            remainder?.let(::recycle); remainder = null; remainderOffset = 0
            closed = false
            lock.notifyAll()
        }
    }

    /**
     * Close the source: wake any blocked read() so it returns -1 and the
     * DSP thread can exit cleanly.
     */
    fun close() {
        synchronized(lock) { closed = true; lock.notifyAll() }
    }

    private fun recycle(block: ShortArray) {
        if (pool.size < MAX_POOLED) pool.addLast(block)
    }

    private companion object {
        const val MAX_BLOCKS      = 25          // ~0.5 s at 20 ms/block
        const val MAX_POOLED      = 8
        const val DROP_LOG_INTERVAL_NS = 5_000_000_000L
    }
}
