package com.llsl.viper4android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.llsl.viper4android.R
import com.llsl.viper4android.capture.ViperCaptureService
import com.llsl.viper4android.capture.ViperShizukuCaptureService
import com.llsl.viper4android.loopback.LoopbackCaptureClient
import com.llsl.viper4android.service.ViperService
import com.llsl.viper4android.ui.navigation.ViperNavigation
import com.llsl.viper4android.ui.theme.ViperTheme
import com.llsl.viper4android.utils.FileLogger
import com.llsl.viper4android.utils.ShizukuShell
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // ── MediaProjection (classic capture mode) ────────────────────────────────

    private var mediaProjectionManager: MediaProjectionManager? = null

    /**
     * Launcher for the MediaProjection consent dialog.
     * Forwards the result to ViperCaptureService on approval.
     */
    private val capturePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK && result.data != null) {
                FileLogger.i(TAG, "MediaProjection consent granted — starting capture service")
                val intent = Intent(this, ViperCaptureService::class.java).apply {
                    action = ViperCaptureService.ACTION_START
                    putExtra(ViperCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ViperCaptureService.EXTRA_RESULT_DATA, result.data)
                }
                startForegroundService(intent)
            } else {
                FileLogger.w(TAG, "MediaProjection consent denied or cancelled")
            }
        }

    // ── lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        mediaProjectionManager =
            getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        // Request Shizuku permission on startup so it's ready when the user
        // chooses the loopback capture path.
        ShizukuShell.requestPermissionIfNeeded { granted ->
            FileLogger.i(TAG, "Shizuku permission: granted=$granted")
        }

        ViperService.startService(this)
        enableEdgeToEdge()
        setContent {
            ViperTheme {
                ViperNavigation(
                    onStartCapture        = { requestMediaProjectionAndStartCapture() },
                    onStopCapture         = { stopCapture() },
                    onStartShizukuCapture = { startShizukuCapture() },
                    onStopShizukuCapture  = { stopShizukuCapture() },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }

    // ── MediaProjection capture (original path) ───────────────────────────────

    fun requestMediaProjectionAndStartCapture() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent() ?: return
        capturePermissionLauncher.launch(intent)
    }

    private fun stopCapture() {
        startService(Intent(this, ViperCaptureService::class.java).apply {
            action = ViperCaptureService.ACTION_STOP
        })
    }

    // ── Shizuku loopback capture (new path) ───────────────────────────────────

    /**
     * Start the Shizuku LOOP_BACK capture service.
     *
     * Unlike the MediaProjection path there is no consent dialog — the
     * AudioPolicy is registered directly by the Shizuku UserService running
     * as shell UID.  The original media audio is silenced on the device
     * (pure loopback) and only the ViPER-processed signal plays.
     *
     * Requires Shizuku to be installed, running, and permission granted.
     */
    fun startShizukuCapture() {
        val client = LoopbackCaptureClient(this)
        if (!client.isShizukuReady()) {
            FileLogger.w(TAG, "Shizuku not ready — cannot start loopback capture")
            Toast.makeText(this,
                getString(R.string.capture_shizuku_unavailable),
                Toast.LENGTH_LONG).show()
            // Try to request permission so the next tap works
            ShizukuShell.requestPermissionIfNeeded { granted ->
                FileLogger.i(TAG, "Shizuku permission after retry: granted=$granted")
            }
            return
        }
        startForegroundService(Intent(this, ViperShizukuCaptureService::class.java).apply {
            action = ViperShizukuCaptureService.ACTION_START
        })
        FileLogger.i(TAG, "Shizuku capture service start requested")
    }

    private fun stopShizukuCapture() {
        startService(Intent(this, ViperShizukuCaptureService::class.java).apply {
            action = ViperShizukuCaptureService.ACTION_STOP
        })
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
