package com.llsl.viper4android.audio

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.llsl.viper4android.utils.FileLogger
import com.llsl.viper4android.utils.ShizukuShell
import rikka.shizuku.SystemServiceHelper
import java.io.FileInputStream
import java.io.InputStreamReader

/**
 * AudioSessionMonitor — detects active audio sessions so ViperService can
 * attach effects to them.
 *
 * Previously used `su -c "dumpsys audio | grep ..."`.
 * Now uses Shizuku's IBinder.dumpAsync() on "media.audio_policy", which is
 * the same technique Wecho's MuteEffectFactory uses.  No root required.
 */
class AudioSessionMonitor(
    context: Context,
    private val onSessionOpen: (sessionId: Int, packageName: String) -> Unit,
    private val onSessionClose: (sessionId: Int) -> Unit,
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activePiids = mutableMapOf<Int, SessionInfo>()
    private var registered = false
    private var pendingResolve = false

    private data class SessionInfo(
        val sessionId: Int,
        val packageName: String,
    )

    private val playbackCallback =
        object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                handlePlaybackChange()
            }
        }

    fun start() {
        if (registered) return
        FileLogger.i(TAG, "Starting playback monitor")
        audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        registered = true
        resolveFromDump()
    }

    fun stop() {
        if (!registered) return
        audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        registered = false
        activePiids.clear()
        FileLogger.i(TAG, "Playback monitor stopped")
    }

    private fun handlePlaybackChange() {
        if (pendingResolve) return
        pendingResolve = true
        Thread {
            try {
                resolveFromDump()
            } finally {
                pendingResolve = false
            }
        }.start()
    }

    // ── session discovery ────────────────────────────────────────────────────

    private fun resolveFromDump() {
        if (!ShizukuShell.isAvailable()) {
            FileLogger.w(TAG, "Shizuku not available — skipping session discovery")
            return
        }

        val dump = dumpAudioPolicy() ?: run {
            FileLogger.w(TAG, "audio_policy dump returned null")
            return
        }

        val discovered = parseActivePlayers(dump) ?: return

        mainHandler.post {
            val discoveredPiids = discovered.keys
            val currentPiids = activePiids.keys.toSet()

            val newPiids = discoveredPiids - currentPiids
            val gonePiids = currentPiids - discoveredPiids

            for (piid in newPiids) {
                val info = discovered[piid] ?: continue
                if (info.sessionId <= 0) continue
                val alreadyTracked = activePiids.values.any { it.sessionId == info.sessionId }
                activePiids[piid] = info
                if (!alreadyTracked) {
                    FileLogger.i(
                        TAG,
                        "New session piid=$piid session=${info.sessionId} (${info.packageName})",
                    )
                    onSessionOpen(info.sessionId, info.packageName)
                } else {
                    FileLogger.d(
                        TAG,
                        "Additional piid=$piid for existing session=${info.sessionId} (${info.packageName})",
                    )
                }
            }

            for (piid in gonePiids) {
                val info = activePiids.remove(piid) ?: continue
                if (info.sessionId > 0) {
                    val stillActive = activePiids.values.any { it.sessionId == info.sessionId }
                    FileLogger.i(
                        TAG,
                        "Piid ended piid=$piid session=${info.sessionId} (${info.packageName})" +
                            " stillActive=$stillActive",
                    )
                    if (!stillActive) {
                        onSessionClose(info.sessionId)
                    }
                }
            }
        }
    }

    /**
     * Dump the audio_policy service via Shizuku's IBinder.dumpAsync().
     * This is the same technique used by Wecho's MuteEffectFactory.dumpAll2().
     * Requires the DUMP permission, which Shizuku grants at shell level.
     */
    private fun dumpAudioPolicy(): String? {
        return try {
            val pipe = ParcelFileDescriptor.createPipe()
            val readPipe = pipe[0]
            val writePipe = pipe[1]

            val binder = SystemServiceHelper.getSystemService("media.audio_policy")
                ?: run {
                    FileLogger.w(TAG, "media.audio_policy binder not found, trying 'audio'")
                    SystemServiceHelper.getSystemService("audio")
                } ?: return null

            binder.dumpAsync(writePipe.fileDescriptor, emptyArray())
            writePipe.close()

            val result = FileInputStream(readPipe.fileDescriptor).use { fis ->
                InputStreamReader(fis, Charsets.UTF_8).readText()
            }
            readPipe.close()
            result
        } catch (e: Exception) {
            FileLogger.e(TAG, "dumpAudioPolicy failed", e)
            null
        }
    }

    private fun parseActivePlayers(text: String): Map<Int, SessionInfo>? =
        try {
            val playerSessions = mutableMapOf<Int, SessionInfo>()
            for (line in text.lineSequence()) {
                val trimmed = line.trim()
                val piid = PIID_PATTERN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull()
                    ?: continue
                val sid = SESSION_PATTERN.find(trimmed)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val pkg = PACKAGE_PATTERN.find(trimmed)?.groupValues?.get(1) ?: ""
                if (sid > 0 && (piid !in playerSessions || pkg.isNotEmpty())) {
                    playerSessions[piid] = SessionInfo(sid, pkg)
                }
            }

            val activePiidSet = mutableSetOf<Int>()
            for (line in text.lineSequence()) {
                if (!line.contains("state:started")) continue
                val piid = PIID_PATTERN.find(line)?.groupValues?.get(1)?.toIntOrNull() ?: continue
                activePiidSet.add(piid)
            }

            val result = mutableMapOf<Int, SessionInfo>()
            for (piid in activePiidSet) {
                val info = playerSessions[piid] ?: continue
                result[piid] = info
            }
            result
        } catch (e: Exception) {
            FileLogger.e(TAG, "parseActivePlayers failed", e)
            null
        }

    companion object {
        private const val TAG = "SessionMonitor"

        private val PIID_PATTERN = Regex("""piid:(\d+)""")
        private val SESSION_PATTERN = Regex("""session(?:Id)?:(\d+)""")
        private val PACKAGE_PATTERN = Regex("""package:(\S+)""")
    }
}
