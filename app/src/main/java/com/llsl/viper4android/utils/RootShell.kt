package com.llsl.viper4android.utils

import java.io.File

/**
 * RootShell — legacy facade kept for backward compatibility.
 *
 * All implementations now delegate to [ShizukuShell].
 * No caller needs to change its import; the root-based `su` path is gone.
 */
object RootShell {

    private const val TAG = "RootShell"

    // ── compatibility shims ──────────────────────────────────────────────────

    fun exec(
        command: String,
        timeoutSec: Long = 10L,
    ): Process = ShizukuShell.exec(command, timeoutSec)

    fun startShell(): Process = ShizukuShell.startShell()

    /** @deprecated Use ShizukuShell.isAvailable() directly. */
    fun isRootAvailable(): Boolean = ShizukuShell.isAvailable()

    /** @deprecated Kept only for DebugLogDialog stream launch. Delegates to Shizuku. */
    fun getSuPath(): String {
        // DebugLogDialog does: ProcessBuilder(getSuPath()).start(), then writes
        // "logcat ..." to stdin.  We return a sentinel value and the dialog
        // now calls ShizukuShell.startShell() directly — this path is no longer
        // reachable after DebugLogDialog is updated.
        throw UnsupportedOperationException(
            "getSuPath() is not supported in Shizuku mode. " +
                "Use ShizukuShell.startShell() instead."
        )
    }

    fun copyFile(
        src: File,
        destPath: String,
    ) = ShizukuShell.copyFile(src, destPath)
}
