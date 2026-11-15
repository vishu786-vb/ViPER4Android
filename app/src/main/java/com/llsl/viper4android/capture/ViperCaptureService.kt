package com.llsl.viper4android.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.IBinder
import android.os.Process
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.llsl.viper4android.R
import com.llsl.viper4android.audio.EffectDispatcher
import com.llsl.viper4android.data.repository.ViperRepository
import com.llsl.viper4android.utils.FileLogger
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ViperCaptureService — standard MediaProjection capture mode.
 *
 * Architecture:
 *
 *   MediaProjection (screen-capture API)
 *       |  AudioPlaybackCaptureConfiguration
 *   AudioRecord  reads raw PCM from other apps' audio
 *       |  FloatArray frames
 *   ViperCaptureEngine.process()  — full ViPERDSP effect chain in native code
 *       |  processed FloatArray
 *   AudioTrack  plays back processed audio
 *
 * Note: The original audio from other apps still plays simultaneously.
 * The user hears both the original and processed audio.
 */
@AndroidEntryPoint
class ViperCaptureService : Service() {

    @Inject
    lateinit var repository: ViperRepository

    companion object {
        const val TAG = "ViperCapture"

        const val ACTION_START = "com.llsl.viper4android.capture.START"
        const val ACTION_STOP  = "com.llsl.viper4android.capture.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val SAMPLE_RATE = 48000
        private const val CHUNK_FRAMES = 512        // frames per processing block
        private const val NOTIFICATION_ID = 42

        @Volatile var isCapturing = false
            private set

        @Volatile var processingLatencyMs = 0.0
            private set
    }

    // -- state --

    private val engine = ViperCaptureEngine()
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val surroundWidener = SurroundWidener()

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var processThread: Thread? = null

    private var headsetReceiverRegistered = false
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            FileLogger.d(TAG, "Headset event: ${intent.action}")
        }
    }

    // -- lifecycle --

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCapture()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                if (isCapturing) return START_NOT_STICKY
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData: Intent? = intent.getParcelableExtra(EXTRA_RESULT_DATA)
                if (resultData == null) {
                    FileLogger.e(TAG, "No MediaProjection result data")
                    stopSelf(); return START_NOT_STICKY
                }
                startForegroundNotification()
                mediaProjection =
                    mediaProjectionManager?.getMediaProjection(resultCode, resultData)
                if (mediaProjection == null) {
                    FileLogger.e(TAG, "getMediaProjection returned null")
                    stopSelf(); return START_NOT_STICKY
                }
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

    // -- capture pipeline --

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startCapture() {
        isCapturing = true

        // Build the AudioRecord with MediaProjection capture config
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .excludeUid(Process.myUid())
            .build()

        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val recMinBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val playMinBuf = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_FLOAT
        )

        audioRecord = try {
            AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(recMinBuf * 2)
                .setAudioPlaybackCaptureConfig(captureConfig)
                .build()
        } catch (e: Exception) {
            FileLogger.e(TAG, "AudioRecord build failed", e)
            isCapturing = false; return
        }

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
                .setBufferSizeInBytes(playMinBuf * 2)
                .build()
        } catch (e: Exception) {
            FileLogger.e(TAG, "AudioTrack build failed", e)
            audioRecord?.release(); isCapturing = false; return
        }

        // Load the full current effect state into the JNI engine
        engine.create(SAMPLE_RATE)
        applyCurrentEffectState()

        // Register headset events
        if (!headsetReceiverRegistered) {
            registerReceiver(headsetReceiver, IntentFilter().apply {
                addAction(AudioManager.ACTION_AUDIO_BECOMING_NOISY)
                addAction(Intent.ACTION_HEADSET_PLUG)
            })
            headsetReceiverRegistered = true
        }

        audioRecord?.startRecording()
        audioTrack?.play()

        // Attach surround widener to the AudioTrack's session.
        // DynamicsProcessing applies per-channel EQ on the output in real-time.
        val trackSessionId = audioTrack?.audioSessionId ?: 0
        if (trackSessionId != 0) {
            surroundWidener.attach(trackSessionId)
            surroundWidener.setIntensity(getSurroundIntensityFromPrefs())
            surroundWidener.enabled = getSurroundEnabledFromPrefs()
        }

        // Register engine in the bridge so ViperService forwards live param changes
        CaptureParamBridge.setEngine(engine)

        // Processing loop
        val samplesPerChunk = CHUNK_FRAMES * 2   // stereo interleaved
        processThread = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val inBuf  = FloatArray(samplesPerChunk)
            val outBuf = FloatArray(samplesPerChunk)
            var latSum = 0.0; var latCnt = 0

            while (!Thread.currentThread().isInterrupted) {
                audioRecord?.read(inBuf, 0, samplesPerChunk, AudioRecord.READ_BLOCKING)

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
        }, "ViperCaptureThread")
        processThread?.start()

        FileLogger.i(TAG, "Capture started")
    }

    private fun stopCapture() {
        isCapturing = false

        CaptureParamBridge.setEngine(null)
        surroundWidener.release()

        if (headsetReceiverRegistered) {
            unregisterReceiver(headsetReceiver)
            headsetReceiverRegistered = false
        }

        processThread?.interrupt()
        processThread = null

        audioRecord?.stop(); audioRecord?.release(); audioRecord = null
        audioTrack?.pause(); audioTrack?.flush()
        audioTrack?.stop(); audioTrack?.release(); audioTrack = null

        engine.release()

        mediaProjection?.stop(); mediaProjection = null

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        FileLogger.i(TAG, "Capture stopped")
    }

    // -- effect state --

    private suspend fun applyCurrentEffectState() {
        try {
            val state = EffectDispatcher.loadFullStateFromPrefs(repository)
            val adapter = ViperEffectAdapter(engine)
            EffectDispatcher.dispatchFullState(
                effect = adapter,
                state  = state,
                masterEnabled = true,
            )
            FileLogger.i(TAG, "Effect state applied to capture engine")
        } catch (e: Exception) {
            FileLogger.e(TAG, "applyCurrentEffectState failed", e)
        }
    }

    // -- surround widener control --

    private fun getSurroundEnabledFromPrefs(): Boolean {
        val prefs = getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
        return prefs.getBoolean("surround_enabled", false)
    }

    private fun getSurroundIntensityFromPrefs(): Float {
        val prefs = getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
        return prefs.getFloat("surround_intensity", 0.5f)
    }

    /**
     * Enable or disable the surround widening effect at runtime.
     * Call from UI via a bound service or broadcast.
     */
    fun setSurroundEnabled(enabled: Boolean) {
        val prefs = getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("surround_enabled", enabled).apply()
        surroundWidener.enabled = enabled
        FileLogger.i(TAG, "Surround widener enabled=$enabled")
    }

    /**
     * Update the surround widening intensity at runtime.
     * @param intensity 0.0 (off) to 1.0 (maximum stereo separation)
     */
    fun setSurroundIntensity(intensity: Float) {
        val prefs = getSharedPreferences("viper_capture_prefs", Context.MODE_PRIVATE)
        prefs.edit().putFloat("surround_intensity", intensity).apply()
        surroundWidener.setIntensity(intensity)
        FileLogger.i(TAG, "Surround intensity=$intensity")
    }

    // -- notification --

    private fun startForegroundNotification() {
        val channelId = "viper_capture"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(channelId, "ViPER Capture",
                NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false); setSound(null, null); enableVibration(false)
            }
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle(getString(R.string.capture_notification_title))
            .setContentText(getString(R.string.capture_notification_text))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
}
