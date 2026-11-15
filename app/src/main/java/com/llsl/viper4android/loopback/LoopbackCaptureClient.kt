package com.llsl.viper4android.loopback

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.llsl.viper4android.utils.FileLogger
import rikka.shizuku.Shizuku
import java.lang.ref.WeakReference

/**
 * App-side façade for the Shizuku loopback capture.
 *
 * Flow:
 *   1. start() — binds the Shizuku UserService via Shizuku.bindUserService().
 *   2. onServiceConnected: calls ILoopbackCapture.start(callback).
 *   3. UserService fires onStarted() then streams onPcm() ~every 20 ms.
 *   4. Each onPcm block is offered to [pcmSource].
 *   5. ViperShizukuCaptureService reads from [pcmSource] like AudioRecord.read().
 *   6. stop() calls service.stop() and unbinds.
 */
class LoopbackCaptureClient(private val context: Context) {

    private val TAG = "LoopbackClient"

    // ── public listener interface ─────────────────────────────────────────────

    interface Listener {
        fun onStarted(sampleRate: Int, channels: Int)
        fun onError(code: Int, message: String)
        fun onStopped()
    }

    /** PCM data queue; consumed by the DSP processing loop. */
    val pcmSource = LoopbackPcmSource()

    var listener: Listener? = null

    // ── internal state ────────────────────────────────────────────────────────

    private val lock = Object()
    private var serviceRef: ILoopbackCapture? = null
    private var connectionRef: ServiceConnection? = null
    private var generation = 0L
    private var startRequested = false

    private fun isActive(gen: Long): Boolean =
        synchronized(lock) { startRequested && generation == gen }

    // ── AIDL callback stub ────────────────────────────────────────────────────
    // Held by native binder — must not strongly reference the client so the
    // entire client graph (including listener) doesn't leak after unbind.

    private class ClientCallback(
        client: LoopbackCaptureClient,
        private val gen: Long,
    ) : ILoopbackCaptureCallback.Stub() {

        private val ref = WeakReference(client)

        override fun onStarted(sampleRate: Int, channels: Int) {
            val c = ref.get() ?: return
            if (!c.isActive(gen)) return
            FileLogger.i("LoopbackCallback", "Capture started: $sampleRate Hz, $channels ch")
            c.listener?.onStarted(sampleRate, channels)
        }

        override fun onPcm(pcm: ByteArray) {
            val c = ref.get() ?: return
            synchronized(c.lock) {
                if (c.startRequested && c.generation == gen) {
                    c.pcmSource.offer(pcm)
                }
            }
        }

        override fun onError(code: Int, message: String) {
            val c = ref.get() ?: return
            if (!c.isActive(gen)) return
            FileLogger.e("LoopbackCallback", "Error $code: $message")
            c.listener?.onError(code, message)
        }

        override fun onStopped() {
            val c = ref.get() ?: return
            if (!c.isActive(gen)) return
            c.listener?.onStopped()
        }
    }

    // ── ServiceConnection ─────────────────────────────────────────────────────

    private inner class LoopbackServiceConnection(
        private val gen: Long,
        private val cb: ILoopbackCaptureCallback,
    ) : ServiceConnection {

        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val service = ILoopbackCapture.Stub.asInterface(binder)
            val active: Boolean
            synchronized(lock) {
                active = startRequested && generation == gen && connectionRef === this
                if (active) serviceRef = service
            }
            if (!active) {
                // Stale connection delivered after unbind — ignore
                FileLogger.w(TAG, "Ignoring stale UserService connection (gen $gen)")
                runCatching {
                    Shizuku.unbindUserService(userServiceArgs(), this, true)
                }
                return
            }
            FileLogger.i(TAG, "UserService connected (gen $gen)")
            runCatching { service.start(cb) }.onFailure { err ->
                FileLogger.e(TAG, "start() on UserService failed: ${err.message}")
                if (isActive(gen)) listener?.onError(ERROR_START_FAILED, err.message ?: "start failed")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            val active: Boolean
            synchronized(lock) {
                active = startRequested && generation == gen && connectionRef === this
                if (active) serviceRef = null
            }
            if (!active) return
            FileLogger.w(TAG, "UserService disconnected unexpectedly")
            listener?.onError(ERROR_SERVICE_DISCONNECTED, "UserService disconnected")
        }
    }

    // ── public control ────────────────────────────────────────────────────────

    fun isShizukuReady(): Boolean =
        Shizuku.pingBinder() &&
            runCatching {
                Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            }.getOrDefault(false)

    /**
     * Bind the Shizuku UserService and start capture.
     * Requires Shizuku to be alive and permission granted.
     */
    fun start() {
        check(isShizukuReady()) { "Shizuku not ready or permission not granted" }
        synchronized(lock) {
            if (startRequested) {
                FileLogger.d(TAG, "start() ignored — already running")
                return
            }
            generation++
            startRequested = true
            pcmSource.clear()
            val cb: ILoopbackCaptureCallback = ClientCallback(this, generation)
            val conn = LoopbackServiceConnection(generation, cb)
            connectionRef = conn
            try {
                Shizuku.bindUserService(userServiceArgs(), conn)
            } catch (err: Throwable) {
                startRequested = false
                connectionRef = null
                pcmSource.close()
                throw err
            }
        }
    }

    /**
     * Stop capture and unbind the UserService.
     * Safe to call from any thread.
     */
    fun stop() {
        val service: ILoopbackCapture?
        val conn: ServiceConnection?
        synchronized(lock) {
            startRequested = false
            generation++
            service = serviceRef
            conn    = connectionRef
            serviceRef    = null
            connectionRef = null
            // close() so a blocked read() wakes up with -1
            pcmSource.close()
        }
        runCatching { service?.stop() }
            .onFailure { FileLogger.w(TAG, "remote stop() failed: ${it.message}") }
        if (conn != null) {
            runCatching { Shizuku.unbindUserService(userServiceArgs(), conn, true) }
                .onFailure { FileLogger.w(TAG, "unbindUserService failed: ${it.message}") }
        }
    }

    // ── Shizuku UserService descriptor ───────────────────────────────────────

    private fun userServiceArgs(): Shizuku.UserServiceArgs =
        Shizuku.UserServiceArgs(
            ComponentName(context, LoopbackCaptureUserService::class.java)
        )
            .daemon(false)
            .processNameSuffix("loopback")
            .debuggable(false)
            .version(VERSION)

    private companion object {
        const val VERSION = 1
        const val ERROR_START_FAILED         = 1
        const val ERROR_SERVICE_DISCONNECTED = 100
    }
}
