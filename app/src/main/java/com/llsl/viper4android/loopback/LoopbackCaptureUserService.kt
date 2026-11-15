package com.llsl.viper4android.loopback

import android.content.Context
import android.media.AudioRecord
import android.os.IBinder
import android.os.Process
import com.llsl.viper4android.utils.FileLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Shizuku UserService — lives in the shell UID process.
 *
 * Owns the LOOP_BACK AudioPolicy (created via hidden reflection API) and an
 * AudioRecord sink that pumps PCM back to the app via ILoopbackCaptureCallback.
 *
 * Key design points:
 *   - All AudioPolicy work happens OFF the binder thread (shell-UID process
 *     vs app-process permission checking differ by calling context).
 *   - Excludes appUid (obtained via Binder.getCallingUid()) from the loopback
 *     mix rule so the app's own AudioTrack output never feeds back.
 *   - linkToDeath on the callback binder: if the app is killed the UserService
 *     stops the capture itself rather than leaking the policy forever.
 *   - PCM block size: 20 ms (1920 frames @ 48 kHz, 7680 bytes PCM16 stereo).
 */
class LoopbackCaptureUserService(private val context: Context) : ILoopbackCapture.Stub() {

    private val TAG = "LoopbackUserService"

    private var policy: LoopbackAudioPolicy? = null
    private var record: AudioRecord? = null
    private var appUid = 0
    private val running = AtomicBoolean(false)
    private val stateLock = Object()

    @Volatile private var callback: ILoopbackCaptureCallback? = null
    @Volatile private var captureThread: Thread? = null
    private var deathRecipient: IBinder.DeathRecipient? = null

    // ── ILoopbackCapture implementation ──────────────────────────────────────

    override fun start(callback: ILoopbackCaptureCallback) {
        // FileLogger may not have trees planted in the UserService process
        // (Application.onCreate never runs there), but it is safe to call.
        check(running.compareAndSet(false, true)) {
            "Loopback capture is already running"
        }
        this.callback = callback

        // Watch for app death: stop capture to avoid orphaning the AudioPolicy
        val recipient = IBinder.DeathRecipient {
            try { stop() } catch (_: Exception) { }
        }
        deathRecipient = recipient
        try { callback.asBinder().linkToDeath(recipient, 0) } catch (_: Exception) { }

        // Record the calling app UID on the binder thread (getCallingUid() is
        // only valid here) then do all AudioPolicy work off-binder.
        appUid = android.os.Binder.getCallingUid()

        val captureCallback = callback   // local copy for the thread

        val thread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            try {
                // Reassign context attribution to shell before creating the policy
                val shellContext = context.installShellAttribution()

                val createdPolicy = LoopbackAudioPolicy(shellContext, appUid)
                policy = createdPolicy
                val createdRecord = createdPolicy.openRecord()
                record = createdRecord

                if (!running.get()) throw InterruptedException("Stopped during startup")
                createdRecord.startRecording()
                if (!running.get()) throw InterruptedException("Stopped during startup")

                try { captureCallback.onStarted(SAMPLE_RATE, CHANNELS) } catch (_: Exception) { }
                FileLogger.i(TAG, "Capture started ($SAMPLE_RATE Hz, $CHANNELS ch)")

                pump(createdRecord, captureCallback)

            } catch (error: Throwable) {
                if (running.get()) {
                    FileLogger.e(TAG, "Capture failed: ${error.message}")
                    try {
                        captureCallback.onError(
                            ERROR_START_FAILED,
                            error.message ?: error.javaClass.simpleName,
                        )
                    } catch (_: Exception) { }
                }
            } finally {
                running.set(false)
                closeCapture()
                synchronized(stateLock) {
                    if (captureThread === Thread.currentThread()) captureThread = null
                }
            }
        }, "ViperLoopbackCapture")
        synchronized(stateLock) { captureThread = thread }
        thread.start()
    }

    override fun stop() {
        FileLogger.i(TAG, "stop() called")
        running.set(false)
        val stoppedCallback = callback
        val thread = synchronized(stateLock) { captureThread }

        // AudioRecord.stop() unblocks the blocking read in pump()
        try { record?.stop() } catch (_: Exception) { }
        thread?.interrupt()

        // Wait for the capture thread to fully exit
        if (thread != null && thread !== Thread.currentThread()) {
            var interrupted = false
            while (thread.isAlive) {
                try { thread.join() } catch (_: InterruptedException) { interrupted = true }
            }
            if (interrupted) Thread.currentThread().interrupt()
        }
        try { stoppedCallback?.onStopped() } catch (_: Exception) { }
    }

    override fun isRunning(): Boolean = running.get()

    // ── PCM pump ──────────────────────────────────────────────────────────────

    private fun pump(record: AudioRecord, callback: ILoopbackCaptureCallback) {
        val pcm = ShortArray(PCM_BLOCK_FRAMES * CHANNELS)
        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                // Fill the full block before sending (avoids variable-size blocks)
                var offset = 0
                while (offset < pcm.size && running.get()) {
                    val read = record.read(
                        pcm, offset, pcm.size - offset, AudioRecord.READ_BLOCKING
                    )
                    if (read <= 0) throw IllegalStateException("Read failed: $read")
                    offset += read
                }
                if (!running.get()) break

                // PCM16 little-endian byte packing
                val bytes = ByteArray(pcm.size * 2)
                for (i in pcm.indices) {
                    bytes[2 * i]     = (pcm[i].toInt() and 0xFF).toByte()
                    bytes[2 * i + 1] = (pcm[i].toInt() ushr 8 and 0xFF).toByte()
                }
                try { callback.onPcm(bytes) } catch (_: Exception) { break }
            }
        } catch (error: Throwable) {
            if (running.get()) {
                FileLogger.e(TAG, "PCM pump died: ${error.message}")
                try {
                    callback.onError(
                        ERROR_PUMP_DIED,
                        error.message ?: error.javaClass.simpleName,
                    )
                } catch (_: Exception) { }
            }
        }
    }

    // ── cleanup ───────────────────────────────────────────────────────────────

    private fun closeCapture() {
        try { record?.stop() } catch (_: Exception) { }
        try { record?.release() } catch (_: Exception) { }
        record = null
        try { policy?.close() } catch (_: Exception) { }
        policy = null
        deathRecipient?.let { r ->
            try { callback?.asBinder()?.unlinkToDeath(r, 0) } catch (_: Exception) { }
        }
        deathRecipient = null
        callback = null
    }

    companion object {
        const val SAMPLE_RATE      = 48_000
        const val CHANNELS         = 2
        const val PCM_BLOCK_FRAMES = SAMPLE_RATE / 50  // 20 ms blocks

        const val ERROR_START_FAILED = 1
        const val ERROR_PUMP_DIED    = 2
    }
}
