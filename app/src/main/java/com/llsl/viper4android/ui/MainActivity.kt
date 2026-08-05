package com.llsl.viper4android.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.llsl.viper4android.capture.ViperCaptureService
import com.llsl.viper4android.service.ViperService
import com.llsl.viper4android.ui.navigation.ViperNavigation
import com.llsl.viper4android.ui.theme.ViperTheme
import com.llsl.viper4android.utils.FileLogger
import com.llsl.viper4android.utils.ShizukuShell
import dagger.hilt.android.AndroidEntryPoint
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    // ── Shizuku ──────────────────────────────────────────────────────────────

    private val shizukuPermissionListener =
        OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                val granted = grantResult == PackageManager.PERMISSION_GRANTED
                FileLogger.i(TAG, "Shizuku permission result: granted=$granted")
            }
        }

    // ── MediaProjection (for capture mode) ───────────────────────────────────

    private var mediaProjectionManager: MediaProjectionManager? = null

    /**
     * Launcher for the MediaProjection consent dialog.
     * When the user approves, we forward the result directly to ViperCaptureService.
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

        Shizuku.addRequestPermissionResultListener(shizukuPermissionListener)

        ShizukuShell.requestPermissionIfNeeded { granted ->
            FileLogger.i(TAG, "Shizuku ready: granted=$granted")
            if (granted) ViperService.startService(this)
        }

        ViperService.startService(this)
        enableEdgeToEdge()
        setContent {
            ViperTheme {
                ViperNavigation(
                    onStartCapture = { requestMediaProjectionAndStartCapture() },
                    onStopCapture  = { stopCapture() },
                )
            }
        }
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener)
        super.onDestroy()
    }

    // ── capture control (called from UI) ─────────────────────────────────────

    fun requestMediaProjectionAndStartCapture() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent() ?: return
        capturePermissionLauncher.launch(intent)
    }

    private fun stopCapture() {
        val intent = Intent(this, ViperCaptureService::class.java).apply {
            action = ViperCaptureService.ACTION_STOP
        }
        startService(intent)
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val SHIZUKU_REQUEST_CODE = 2001
    }
}
