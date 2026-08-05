package com.llsl.viper4android.utils

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import rikka.shizuku.Shizuku.OnRequestPermissionResultListener
import rikka.shizuku.ShizukuRemoteProcess
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * ShizukuShell — replaces RootShell entirely.
 *
 * Runs shell commands as UID 2000 (shell) via the Shizuku API instead of
 * spawning an `su` binary.  The public API is kept intentionally identical
 * to RootShell so every call-site only needs a one-line import swap.
 *
 * Permission lifecycle:
 *   Call [requestPermissionIfNeeded] once from an Activity (MainActivity).
 *   After that, [isAvailable] can be polled anywhere.
 */
object ShizukuShell {

    private const val TAG = "ShizukuShell"
    private const val PERMISSION_REQUEST_CODE = 2001
    private const val EXEC_TIMEOUT_SEC = 10L

    // ── permission state ────────────────────────────────────────────────────

    @Volatile
    private var permissionGranted = false

    fun isAvailable(): Boolean {
        if (permissionGranted) return true
        if (!Shizuku.pingBinder()) return false
        return try {
            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            if (granted) permissionGranted = true
            granted
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Call this from MainActivity.onCreate / onResume.
     * [onResult] is invoked on the main thread with true/false.
     */
    fun requestPermissionIfNeeded(onResult: (granted: Boolean) -> Unit) {
        if (!Shizuku.pingBinder()) {
            FileLogger.w(TAG, "Shizuku not running")
            onResult(false)
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            permissionGranted = true
            onResult(true)
            return
        }
        val listener = object : OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode == PERMISSION_REQUEST_CODE) {
                    val granted = grantResult == PackageManager.PERMISSION_GRANTED
                    permissionGranted = granted
                    FileLogger.i(TAG, "Permission result: granted=$granted")
                    onResult(granted)
                    Shizuku.removeRequestPermissionResultListener(this)
                }
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
        } catch (e: Exception) {
            FileLogger.e(TAG, "requestPermission failed", e)
            Shizuku.removeRequestPermissionResultListener(listener)
            onResult(false)
        }
    }

    // ── core execution ───────────────────────────────────────────────────────

    /**
     * Execute a shell command and wait for it to finish.
     * Returns the [Process] (already waited on) so callers can read stdout/stderr.
     * Throws RuntimeException if Shizuku is unavailable or the command times out.
     *
     * Note: ShizukuRemoteProcess.waitFor() throws IllegalArgumentException
     * ("process hasn't exited") instead of returning false when the timeout
     * elapses — we treat that identically to a timeout.
     */
    fun exec(
        command: String,
        timeoutSec: Long = EXEC_TIMEOUT_SEC,
    ): Process {
        checkAvailable()
        val process = newProcess(arrayOf("sh", "-c", command))
        process.outputStream.close()
        val finished = try {
            process.waitFor(timeoutSec, TimeUnit.SECONDS)
        } catch (e: IllegalArgumentException) {
            // Shizuku throws this when the process hasn't exited yet within the
            // timeout — treat it the same as waitFor returning false.
            FileLogger.w(TAG, "waitFor threw IllegalArgumentException for: $command")
            false
        }
        if (!finished) {
            process.destroyForcibly()
            throw RuntimeException("Shizuku command timed out after ${timeoutSec}s: $command")
        }
        return process
    }

    /**
     * Execute a command and return its stdout as a String.
     * Returns empty string on failure — logs a warning, never an error,
     * since timeout is expected for some Shizuku commands.
     */
    fun execAndRead(
        command: String,
        timeoutSec: Long = EXEC_TIMEOUT_SEC,
    ): String {
        return try {
            val process = exec(command, timeoutSec)
            process.inputStream.bufferedReader().readText()
        } catch (e: RuntimeException) {
            // Timeout is expected for some commands — downgrade to warning
            FileLogger.w(TAG, "execAndRead timed out: $command")
            ""
        } catch (e: Exception) {
            FileLogger.w(TAG, "execAndRead failed: $command — ${e.message}")
            ""
        }
    }

    /**
     * Launch a command via Shizuku without waiting for exit.
     * Use for fire-and-forget operations (pm grant, appops set) where
     * ShizukuRemoteProcess never signals clean termination.
     */
    fun fireAndForget(command: String) {
        if (!isAvailable()) return
        try {
            val process = newProcess(arrayOf("sh", "-c", command))
            process.outputStream.close()
            // Deliberately not waiting — caller doesn't need the result
        } catch (e: Exception) {
            FileLogger.w(TAG, "fireAndForget failed: $command — ${e.message}")
        }
    }

    /**
     * Start a long-lived shell session (e.g. streaming logcat).
     * The caller is responsible for closing the process.
     */
    fun startShell(): Process {
        checkAvailable()
        return newProcess(arrayOf("sh"))
    }

    // ── file operations ──────────────────────────────────────────────────────

    /**
     * Write raw [data] bytes to [destPath] by piping through `cat >` in the
     * Shizuku shell.  This is the stdin-pipe equivalent of the old
     * `Runtime.exec(["su","-c","cat > file"])` pattern.
     */
    fun writeToFile(data: ByteArray, destPath: String) {
        checkAvailable()
        val safe = destPath.replace("'", "")
        val process = newProcess(arrayOf("sh", "-c", "cat > '$safe'"))
        try {
            process.outputStream.use { it.write(data) }
            val finished = try {
                process.waitFor(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: IllegalArgumentException) { false }
            if (!finished) {
                process.destroyForcibly()
                throw RuntimeException("Shizuku writeToFile timed out: $destPath")
            }
        } catch (e: Exception) {
            process.destroyForcibly()
            FileLogger.e(TAG, "writeToFile failed: $destPath", e)
            throw e
        }
    }

    /**
     * Copy [src] to [destPath], using a temp file + atomic rename.
     *
     * Strategy (in order):
     *  1. Direct Java copy if the destination directory is writable by the app.
     *  2. Pipe stdin into `sh -c "cat > tmpPath && mv && chmod"` via Shizuku.
     *
     * This mirrors RootShell.copyFile exactly so every call-site is a
     * drop-in replacement.
     */
    fun copyFile(
        src: File,
        destPath: String,
    ) {
        val destFile = File(destPath)
        val destDir = destFile.parentFile
        val tmpPath = "$destPath.tmp"
        val tmpFile = File(tmpPath)

        // 1 — try a direct Java copy first (works when dest dir is world-writable)
        try {
            if (destDir != null && destDir.canWrite()) {
                if (!destDir.exists()) destDir.mkdirs()
                src.copyTo(tmpFile, overwrite = true)
                tmpFile.renameTo(destFile)
                destFile.setReadable(true, false)
                FileLogger.i(TAG, "Direct copy OK: $destPath")
                return
            }
        } catch (_: Exception) {
            FileLogger.d(TAG, "Direct copy failed for $destPath, trying Shizuku")
        }

        // 2 — pipe through Shizuku shell
        checkAvailable()
        val safeDest = destPath.replace("'", "")
        val safeTmp = tmpPath.replace("'", "")
        try {
            val process = newProcess(
                arrayOf("sh", "-c", "cat > '$safeTmp' && mv '$safeTmp' '$safeDest' && chmod 644 '$safeDest'")
            )
            process.outputStream.use { it.write(src.readBytes()) }
            val finished = try {
                process.waitFor(EXEC_TIMEOUT_SEC, TimeUnit.SECONDS)
            } catch (_: IllegalArgumentException) { false }
            if (!finished) {
                process.destroyForcibly()
                throw RuntimeException("Shizuku copyFile timed out: $destPath")
            }
            FileLogger.i(TAG, "Shizuku pipe copy OK: $destPath")
        } catch (e: Exception) {
            FileLogger.e(TAG, "Shizuku copyFile failed: $destPath", e)
            throw e
        }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    private fun checkAvailable() {
        if (!isAvailable()) {
            throw RuntimeException("Shizuku is not available or permission not granted")
        }
    }

    private fun newProcess(args: Array<String>): Process {
        // Shizuku.newProcess is a hidden API — access it via reflection the same
        // way Wecho's ShizukuHelpMe does.
        val method = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        )
        method.isAccessible = true
        return method.invoke(null, args, null, null) as ShizukuRemoteProcess
    }
}
