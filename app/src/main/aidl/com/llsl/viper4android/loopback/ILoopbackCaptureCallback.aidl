package com.llsl.viper4android.loopback;

/**
 * Oneway callbacks fired by the Shizuku UserService (shell UID) back to the
 * app process.  onPcm() is called ~every 20 ms on the UserService capture
 * thread; keep the implementation fast and non-blocking.
 */
oneway interface ILoopbackCaptureCallback {

    /** Capture is up: sampleRate=48000, channels=2, encoding=PCM16. */
    void onStarted(int sampleRate, int channels);

    /**
     * One block of interleaved PCM16 stereo audio (~20 ms = 1920 frames =
     * 7680 bytes).  Size is always a multiple of a stereo PCM16 frame (4 bytes).
     */
    void onPcm(in byte[] pcm);

    /** Fatal capture error — policy rejected, sink died, etc. */
    void onError(int code, String message);

    /** Capture stopped cleanly (stop() called or UserService died). */
    void onStopped();
}
