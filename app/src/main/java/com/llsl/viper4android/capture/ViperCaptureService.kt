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
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.audiofx.DynamicsProcessing
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.RequiresPermission
import androidx.core.app.NotificationCompat
import com.llsl.viper4android.R
import com.llsl.viper4android.audio.EffectDispatcher
import com.llsl.viper4android.audio.ViperParams
import com.llsl.viper4android.data.repository.ViperRepository
import com.llsl.viper4android.utils.FileLogger
import com.llsl.viper4android.utils.ShizukuShell
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import rikka.shizuku.SystemServiceHelper
import java.io.FileInputStream
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import javax.inject.Inject

/**
 * ViperCaptureService — the root-free alternative to the AIDL driver module.
 *
 * Architecture (mirrors Wecho's AudioCaptureService):
 *
 *   MediaProjection (screen-capture API)
 *       ↓  AudioPlaybackCaptureConfiguration
 *   AudioRecord  reads raw PCM from other apps' audio
 *       ↓  FloatArray frames
 *   ViperCaptureEngine.process()  — full ViPERDSP effect chain in native code
 *       ↓  processed FloatArray
 *   AudioTrack  plays back processed audio
 *       +
 *   Shizuku MuteEffectFactory  — silences original app sessions via
 *       DynamicsProcessing(-144 dB) so there's no doubled audio.
 *
 * Shizuku is used for:
 *   1. Granting DUMP + PROJECT_MEDIA app-ops so MediaProjection works
 *      without the screen-capture consent dialog on every start.
 *   2. Dumping audio_policy to discover other apps' sessions for muting.
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

        private val SESSION_REGEX = Regex(
            """Session Id:\s*(\d+)\s+UID:\s*(\d+)[\S\s]*?Attributes:[\S\s]*?Content type:\s*(\w+)\s*Usage:\s*(\w+)"""
        )
        private val SESSION_REGEX_33 = Regex(
            """Session ID:\s*(\d+);\s*uid\s*(\d+);[\S\s]*?Attributes:[\S\s]*?Content type:\s*(\w+)\s*Usage:\s*(\w+)"""
        )
    }

    // ── state ────────────────────────────────────────────────────────────────

    private val engine      = ViperCaptureEngine()
    private val scope       = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val muteEffects = ConcurrentHashMap<Int, DynamicsProcessing>()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var audioTrack: AudioTrack? = null
    private var processThread: Thread? = null

    private var audioManager: AudioManager? = null
    private var playbackCallback: AudioManager.AudioPlaybackCallback? = null

    private var headsetReceiverRegistered = false
    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            FileLogger.d(TAG, "Headset event: ${intent.action}")
            // AudioTrack handles rerouting automatically — no action needed.
        }
    }

    // ── lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
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

    // ── capture pipeline ─────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun startCapture() {
        isCapturing = true

        // Grant the DUMP + PROJECT_MEDIA app-ops via Shizuku so that future
        // MediaProjection starts don't require a consent dialog each time.
        if (ShizukuShell.isAvailable()) {
            grantAppOpsViaShizuku()
        }

        // Build the AudioRecord with MediaProjection capture config
        val captureConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
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

        // Register engine in the bridge so ViperService forwards live param changes
        CaptureParamBridge.setEngine(engine)

        // Kick off the mute loop (Shizuku required)
        if (ShizukuShell.isAvailable()) {
            registerPlaybackCallbackForMute()
            muteOtherSessionsNow()
        }

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

        // Unregister from bridge first so no more params are forwarded
        CaptureParamBridge.setEngine(null)

        if (headsetReceiverRegistered) {
            unregisterReceiver(headsetReceiver)
            headsetReceiverRegistered = false
        }

        playbackCallback?.let { audioManager?.unregisterAudioPlaybackCallback(it) }
        playbackCallback = null

        releaseAllMuteEffects()

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

    // ── effect state ─────────────────────────────────────────────────────────

    /**
     * Load the full current effect state from DataStore and push it to the JNI engine.
     * Uses ViperEffectAdapter so we can call EffectDispatcher.dispatchFullState() directly —
     * no duplication of the param-collection logic that already lives in EffectDispatcher.
     */
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

    // ── Shizuku mute ─────────────────────────────────────────────────────────

    /**
     * Grant DUMP + PROJECT_MEDIA app-ops to ourselves so that:
     *  - We can dump audio_policy to discover sessions for muting.
     *  - MediaProjection doesn't show the consent dialog on every cold start
     *    (PROJECT_MEDIA app-op, same as what Wecho's ShizukuHelpMe does).
     */
    private fun grantAppOpsViaShizuku() {
        try {
            val pkg = packageName
            // Fire-and-forget — don't wait for process exit.
            // ShizukuRemoteProcess doesn't signal termination cleanly for pm/appops
            // commands, so any waitFor() call always times out and throws
            // IllegalArgumentException("process hasn't exited"), producing noise
            // in the debug log even though the grant succeeded.
            ShizukuShell.fireAndForget("pm grant $pkg android.permission.DUMP 2>/dev/null || true")
            ShizukuShell.fireAndForget("appops set $pkg PROJECT_MEDIA allow 2>/dev/null || true")
            FileLogger.i(TAG, "Shizuku app-ops granted")
        } catch (e: Exception) {
            FileLogger.w(TAG, "grantAppOpsViaShizuku: ${e.message}")
        }
    }

    /** Register AudioPlaybackCallback so we re-mute whenever new sessions appear. */
    private fun registerPlaybackCallbackForMute() {
        val cb = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(
                configs: MutableList<android.media.AudioPlaybackConfiguration>
            ) { muteOtherSessionsNow() }
        }
        audioManager?.registerAudioPlaybackCallback(cb, mainHandler)
        playbackCallback = cb
    }

    private fun muteOtherSessionsNow() {
        scope.launch {
            val sessions = dumpAudioSessions() ?: return@launch
            muteExternalSessions(sessions)
        }
    }

    /**
     * Dump media.audio_policy via IBinder.dumpAsync(), exactly like
     * Wecho's MuteEffectFactory.dumpAudioSessionsViaShizuku().
     */
    private fun dumpAudioSessions(): List<SessionInfo>? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val binder = SystemServiceHelper.getSystemService("media.audio_policy")
                ?: SystemServiceHelper.getSystemService("audio")
                ?: return null
            binder.dumpAsync(pipe[1].fileDescriptor, emptyArray())
            pipe[1].close()
            val text = FileInputStream(pipe[0].fileDescriptor).use { fis ->
                InputStreamReader(fis, Charsets.UTF_8).readText()
            }
            pipe[0].close()
            parseSessions(text)
        } catch (e: Exception) {
            FileLogger.e(TAG, "dumpAudioSessions failed", e)
            null
        }
    }

    private data class SessionInfo(
        val sessionId: Int, val uid: Int, val usage: String, val pkgName: String
    )

    private fun parseSessions(text: String): List<SessionInfo> {
        val results = mutableListOf<SessionInfo>()
        val matches = SESSION_REGEX.findAll(text).toList()
            .ifEmpty { SESSION_REGEX_33.findAll(text).toList() }
        for (m in matches) {
            val sid = m.groupValues[1].toIntOrNull() ?: continue
            val uid = m.groupValues[2].toIntOrNull() ?: continue
            val content = m.groupValues[3]
            val usage = m.groupValues[4]
            val pkg = try {
                packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "unknown"
            } catch (_: Exception) { "unknown" }
            results.add(SessionInfo(sid, uid, usage, pkg))
        }
        return results
    }

    /**
     * Apply -144 dB DynamicsProcessing to every external session playing MEDIA/GAME audio.
     * Skip our own sessions so we don't mute the processed AudioTrack output.
     */
    private fun muteExternalSessions(sessions: List<SessionInfo>) {
        val ownSessions = buildSet<Int> {
            audioRecord?.audioSessionId?.let { if (it != 0) add(it) }
            audioTrack?.audioSessionId?.let { if (it != 0) add(it) }
        }

        val currentIds = sessions.map { it.sessionId }.toSet()

        // Release effects for sessions that ended
        val gone = muteEffects.keys.filter { it !in currentIds }
        gone.forEach { sid ->
            muteEffects.remove(sid)?.runCatching { release() }
        }

        for (s in sessions) {
            if (s.sessionId in ownSessions) continue
            if (s.pkgName == packageName) continue
            if (muteEffects.containsKey(s.sessionId)) continue

            val usage = s.usage.uppercase()
            if (!usage.contains("USAGE_MEDIA") &&
                !usage.contains("USAGE_GAME") &&
                !usage.contains("USAGE_UNKNOWN")) continue

            try {
                val fx = DynamicsProcessing(Int.MAX_VALUE, s.sessionId, null)
                fx.setInputGainAllChannelsTo(-144f)
                fx.enabled = true
                muteEffects[s.sessionId] = fx
                FileLogger.d(TAG, "Muted session ${s.sessionId} (${s.pkgName})")
            } catch (e: Exception) {
                FileLogger.w(TAG, "Could not mute session ${s.sessionId}: ${e.message}")
            }
        }
    }

    private fun releaseAllMuteEffects() {
        muteEffects.values.forEach { runCatching { it.release() } }
        muteEffects.clear()
    }

    // ── notification ─────────────────────────────────────────────────────────

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
            .setSmallIcon(R.drawable.ic_notification)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
        startForeground(NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
    }
}
