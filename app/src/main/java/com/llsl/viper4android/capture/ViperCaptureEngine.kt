package com.llsl.viper4android.capture

import com.llsl.viper4android.utils.FileLogger

/**
 * Thin Kotlin wrapper around the native ViPER DSP engine used in capture mode.
 *
 * Lifecycle: create() → process() repeatedly → release()
 */
class ViperCaptureEngine {

    companion object {
        private const val TAG = "ViperCaptureEngine"

        init {
            try {
                System.loadLibrary("vipercapture")
                FileLogger.i(TAG, "libvipercapture loaded")
            } catch (e: UnsatisfiedLinkError) {
                FileLogger.e(TAG, "Failed to load libvipercapture", e)
            }
        }
    }

    // ── JNI declarations ────────────────────────────────────────────────────

    private external fun nativeCreate(sampleRate: Int)
    private external fun nativeRelease()
    private external fun nativeProcess(input: FloatArray, output: FloatArray, frameCount: Int)
    private external fun nativeSetParam(param: Int, val1: Int, val2: Int, val3: Int)
    private external fun nativeSetParamBytes(param: Int, data: ByteArray)
    private external fun nativeSetSampleRate(sampleRate: Int)

    // ── public API ──────────────────────────────────────────────────────────

    fun create(sampleRate: Int = 48000) {
        nativeCreate(sampleRate)
    }

    fun release() {
        nativeRelease()
    }

    /**
     * Process [frameCount] interleaved stereo float frames.
     * [input] and [output] must both be of length frameCount*2.
     */
    fun process(input: FloatArray, output: FloatArray, frameCount: Int) {
        nativeProcess(input, output, frameCount)
    }

    fun setSampleRate(rate: Int) {
        nativeSetSampleRate(rate)
    }

    /** Dispatch a single int param — maps directly to ViPER.DispatchRawParam */
    fun setParam(param: Int, value: Int) {
        nativeSetParam(param, value, 0, 0)
    }

    fun setParam(param: Int, v1: Int, v2: Int) {
        nativeSetParam(param, v1, v2, 0)
    }

    fun setParam(param: Int, v1: Int, v2: Int, v3: Int) {
        nativeSetParam(param, v1, v2, v3)
    }

    /** Dispatch a byte-array param (convolver kernel path, DDC coefficients, etc.) */
    fun setParamBytes(param: Int, data: ByteArray) {
        nativeSetParamBytes(param, data)
    }
}
