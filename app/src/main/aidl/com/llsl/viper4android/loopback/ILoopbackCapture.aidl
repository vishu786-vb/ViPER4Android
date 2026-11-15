package com.llsl.viper4android.loopback;

import com.llsl.viper4android.loopback.ILoopbackCaptureCallback;

/**
 * Controls the loopback capture that runs inside the Shizuku UserService
 * (shell UID process).  The UserService creates a pure LOOP_BACK AudioPolicy,
 * which pulls media audio out of AudioFlinger without a screen-capture dialog,
 * and streams PCM back to the app via ILoopbackCaptureCallback.
 *
 * All calls originate from the app process; the UserService owns the policy.
 */
interface ILoopbackCapture {

    /**
     * Start capture.  Format is fixed by the UserService (48 kHz, stereo,
     * PCM16).  Actual parameters are reported through onStarted().
     * Calling start() while already running reports an error via onError().
     */
    oneway void start(in ILoopbackCaptureCallback callback);

    /** Stop capture and release the AudioPolicy / AudioRecord sink. */
    oneway void stop();

    /**
     * Returns true if capture is currently active.
     * Used by LoopbackCaptureClient to detect a live service after rebind.
     */
    boolean isRunning();
}
