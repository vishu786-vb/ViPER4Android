package com.llsl.viper4android.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.IBinder
import android.os.Process
import androidx.core.app.NotificationCompat
import com.llsl.viper4android.R
import com.llsl.viper4android.audio.EffectDispatcher
import com.llsl.viper4android.data.repository.ViperRepository
import com.llsl.viper4android.loopback.LoopbackCaptureClient
import com.llsl.viper4android.utils.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViperShizukuCaptureService — Shizuku LOOP_BACK capture mode.
 *
 * Architecture:
 *
 *   Shizuku UserService (shell UID)
 *       | LoopbackAudioPolicy (LOOP_BACK, hidden API, requires MODIFY_AUDIO_ROUTING)
 *       |   → pulls ALL matching media audio out of AudioFlinger silently
 *       | AudioRecord sink  → PCM16 stereo 48 kHz, 20 ms blocks
 *       | AIDL ILoopbackCaptureCallback.onPcm()
 *   LoopbackCaptureClient  (app process)
 *       | LoopbackPcmSource (bounded queue, blocking read)
 *   ViperCaptureEngine.process()  — full ViPERDSP effect chain (JNI)
 *       | processed FloatArray
 *   AudioTrack  → device output
 *
 * Advantages over MediaProjection (ViperCaptureService):
 *   - No screen-capture consent dialog needed.
 *   - Pure LOOP_BACK removes the original audio from device output naturally
 *     (the user only hears the processed version — no double-audio).
 *   - No DynamicsProcessing session muting required.
 *
 * Requires Shizuku to be installed, running, and permission granted.
 */
@AndroidEntryPoint
class ViperShizukuCaptureService : Service() {

    @Inject
    lateinit var repository: ViperRepository

    companion object {
        const val TAG = "ViperShizukuCapture"

        const val ACTION_START = "com.llsl.viper4android.capture.shizuku.START"
        const val ACTION_STOP  = "com.llsl.viper4android.capture.shizuku.STOP"

        private const val SAMPLE_RATE    = 48_000
        private const val CHUNK_FRAMES   = 512     // frames per ViPER processing block
        private const val NOTIFICATION_ID = 43

        @Volatile var isCapturing = false
            private set

        @Volatile var processingLatencyMs = 0.0
            private set
    }

    // ── state ─────────────────────────────────────────────────────────────────

    private val engine        = ViperCaptureEngine()
    private val scope         = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val loopbackClient = LoopbackCaptureClient(this)
    private val surroundWidener = SurroundWidener()

    private var audioTrack: AudioTrack? = null
    private var processThread: Thread? = null

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isCapturing) return START_NOT_STICKY
                if (!loopbackClient.isShizukuReady()) {
                    FileLogger.e(TAG, "Shizuku not ready — cannot start loopback capture")
                    stopSelf(); return START_NOT_STICKY
                }
                startForegroundNotification()
                scope.launch { startCapture() }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    // ── capture pipeline ──────────────────────────────────────────────────────

    private suspend fun startCapture() {
        isCapturing = true

        // Build AudioTrack for playback output (matches loopback format)
        val minBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        audioTrack = try {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                        .build()
                )
                .setBufferSizeInBytes(minBuf * 2)
                .build()
        } catch (e: Exception) {
            FileLogger.e(TAG, "AudioTrack build failed", e)
            isCapturing = false; return
        }

        // Init DSP engine and apply current effect state
        engine.create(SAMPLE_RATE)
        applyCurrentEffectState()

        audioTrack?.play()

        // Attach surround widener to our AudioTrack session
        val trackSessionId = audioTrack?.audioSessionId ?: 0
        if (trackSessionId != 0) {
            surroundWidener.attach(trackSessionId)
            surroundWidener.setIntensity(getSurroundIntensity())
            surroundWidener.enabled = getSurroundEnabled()
        }

        CaptureParamBridge.setEngine(engine)

        // Wire loopback listener
        loopbackClient.listener = object : LoopbackCaptureClient.Listener {
            override fun onStarted(sampleRate: Int, channels: Int) {
                FileLogger.i(TAG, "Loopback capture up: $sampleRate Hz, $channels ch")
                startProcessingThread()
            }
            override fun onError(code: Int, message: String) {
                FileLogger.e(TAG, "Loopback error $code: $message")
                stopCapture()
            }
            override fun onStopped() {
                FileLogger.i(TAG, "Loopback stopped")
                stopCapture()
            }
        }

        try {
            loopbackClient.start()
        } catch (e: Exception) {
            FileLogger.e(TAG, "loopbackClient.start() failed: ${e.message}")
            isCapturing = false
            audioTrack?.release(); audioTrack = null
            engine.release()
            CaptureParamBridge.setEngine(null)
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        }

        FileLogger.i(TAG, "Shizuku loopback capture requested")
    }

    /**
     * Start the processing thread after the UserService confirms capture is up.
     * Reads PCM16 shorts from the loopback queue, converts to float, processes
     * through the ViPER DSP engine, and writes to AudioTrack.
     */
    private fun startProcessingThread() {
        val samplesPerChunk = CHUNK_FRAMES * 2   // stereo interleaved
        // PCM16 shorts per chunk — same frame count as the float buffers
        val shortsPerChunk  = samplesPerChunk

        processThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val inShorts = ShortArray(shortsPerChunk)
            val inBuf    = FloatArray(samplesPerChunk)
            val outBuf   = FloatArray(samplesPerChunk)
            var latSum   = 0.0; var latCnt = 0

            while (!Thread.currentThread().isInterrupted) {
                // Blocking read — returns -1 when source is closed
                val read = loopbackClient.pcmSource.read(inShorts, 0, shortsPerChunk)
                if (read < 0) break

                // PCM16 → float [-1, 1]
                for (i in 0 until read) inBuf[i] = inShorts[i] / 32768f
                // Zero-pad if short read (shouldn't happen but be safe)
                for (i in read until samplesPerChunk) inBuf[i] = 0f

                val t0 = System.nanoTime()
                engine.process(inBuf, outBuf, CHUNK_FRAMES)
                latSum += (System.nanoTime() - t0) / 1_000_000.0
                if (++latCnt >= 10) {
                    processingLatencyMs = latSum / latCnt
                    latSum = 0.0; latCnt = 0
                }

                val written = audioTrack?.write(outBuf, 0, samplesPerChunk,
                    AudioTrack.WRITE_BLOCKING) ?: 0
                if (written < 0) FileLogger.w(TAG, "AudioTrack write error: $written")
            }
            FileLogger.i(TAG, "Processing thread exited")
        }, "ViperShizukuThread")
        processThread?.start()
    }

    private fun stopCapture() {
        isCapturing = false

        CaptureParamBridge.setEngine(null)
        surroundWidener.release()

        processThread?.interrupt()
        processThread = null

        loopbackClient.stop()
        loopbackClient.listener = null

        audioTrack?.pause(); audioTrack?.flush()
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null

        engine.release()

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Shizuku capture stopped")
    }

    // ── effect state ──────────────────────────────────────────────────────────

    private suspend fun applyCurrentEffectState() {
        try {
            val state = EffectDispatcher.loadFullStateFromPrefs(repository)
            val adapter = ViperEffectAdapter(engine)
            EffectDispatcher.dispatchFullState(
                effect = adapter,
                state  = state,
                masterEnabled = true,
            )
        } catch (e: Exception) {
            FileLogger.e(TAG, "applyCurrentEffectState failed", e)
        }
    }

    // ── surround widener helpers ──────────────────────────────────────────────

    private fun getSurroundEnabled(): Boolean =
        getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
            .getBoolean("surround_enabled", false)

    private fun getSurroundIntensity(): Float =
        getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
            .getFloat("surround_intensity", 0.5f)

    fun setSurroundEnabled(enabled: Boolean) {
        getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("surround_enabled", enabled).apply()
        surroundWidener.enabled = enabled
    }

    fun setSurroundIntensity(intensity: Float) {
        getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
            .edit().putFloat("surround_intensity", intensity).apply()
        surroundWidener.setIntensity(intensity)
    }

    // ── notification ─────────────────────────────────────────────────────────

    private fun startForegroundNotification() {
        val channelId = "viper_shizuku_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "ViPER Shizuku Capture",
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            }
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_shizuku_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        // specialUse foreground type (no MEDIA_PROJECTION token needed)
        startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
    }
}
