package com.llsl.viper4android.capture

import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.DynamicsProcessing.Eq
import android.media.audiofx.DynamicsProcessing.EqBand
import com.llsl.viper4android.utils.FileLogger

/**
 * SurroundWidener — per-channel EQ-based stereo widening using DynamicsProcessing.
 *
 * Technique:
 * Creates a psychoacoustic "surround" effect by applying asymmetric EQ curves
 * to the left and right channels. This exploits how the brain interprets
 * frequency-dependent level differences between ears as spatial cues:
 *
 *   - Left channel gets a slight boost in upper-mids/presence (2–5 kHz)
 *     and a cut in lower-mids (300–800 Hz)
 *   - Right channel gets the inverse: boost in lower-mids, cut in upper-mids
 *   - Both channels get a shared bass shelf and air boost for fullness
 *
 * The intensity parameter (0.0–1.0) scales how aggressively the asymmetric
 * EQ is applied. At 0.0, both channels have identical flat EQ (no widening).
 * At 1.0, maximum stereo separation is applied.
 *
 * This is the same principle HubeRSoundX and similar apps use — purely
 * amplitude-domain manipulation via Android's official DynamicsProcessing API.
 * No native code, no root, no Shizuku required.
 *
 * Limitations vs true surround (ViPER/Dolby):
 *   - No time-domain delay (Haas effect)
 *   - No HRTF convolution
 *   - No cross-feed between channels
 * But it's perceptually effective for headphone listening and completely
 * stock-Android-API based.
 *
 * Usage:
 *   val widener = SurroundWidener()
 *   widener.attach(audioTrack.audioSessionId)
 *   widener.setIntensity(0.6f)  // 0.0 = off, 1.0 = max
 *   widener.enabled = true
 *   // ... later ...
 *   widener.release()
 */
class SurroundWidener {

    companion object {
        private const val TAG = "SurroundWidener"

        // Band count for our EQ — 6 bands gives us enough resolution
        // to shape the stereo field without excessive CPU overhead
        private const val BAND_COUNT = 6

        // Center frequencies (Hz) for our 6-band EQ
        private val BAND_FREQUENCIES = floatArrayOf(
            60f,      // Sub-bass
            250f,     // Low-mid
            800f,     // Mid
            2500f,    // Upper-mid / presence
            6000f,    // Brilliance
            14000f    // Air
        )

        /**
         * Per-band gain deltas (dB) for the LEFT channel at maximum intensity.
         * Right channel gets the inverse of the asymmetric bands.
         *
         * Bands 0 and 5 (sub-bass and air) are symmetric — they provide
         * fullness/sparkle without contributing to spatial separation.
         * Bands 1-4 are asymmetric — they create the inter-channel
         * frequency difference that the brain interprets as width.
         */
        private val LEFT_GAINS_MAX = floatArrayOf(
            1.5f,     // 60 Hz:   slight bass shelf boost (symmetric)
            -2.0f,    // 250 Hz:  cut low-mids on left
            -1.0f,    // 800 Hz:  slight mid scoop on left
            2.5f,     // 2.5 kHz: boost presence on left
            1.5f,     // 6 kHz:   boost brilliance on left
            1.0f      // 14 kHz:  air boost (symmetric)
        )

        private val RIGHT_GAINS_MAX = floatArrayOf(
            1.5f,     // 60 Hz:   same bass shelf (symmetric)
            2.0f,     // 250 Hz:  boost low-mids on right (inverse of left)
            1.0f,     // 800 Hz:  slight mid boost on right (inverse of left)
            -2.5f,    // 2.5 kHz: cut presence on right (inverse of left)
            -1.5f,    // 6 kHz:   cut brilliance on right (inverse of left)
            1.0f      // 14 kHz:  same air boost (symmetric)
        )
    }

    private var dynamicsProcessing: DynamicsProcessing? = null
    private var currentIntensity: Float = 0.5f
    private var isAttached = false

    /** Whether the effect is currently active. */
    var enabled: Boolean
        get() = dynamicsProcessing?.enabled ?: false
        set(value) {
            dynamicsProcessing?.enabled = value
        }

    /**
     * Attach the surround widener to an audio session.
     *
     * @param audioSessionId The session ID of the AudioTrack to process.
     *        Use `audioTrack.audioSessionId` from your playback track.
     * @return true if successfully attached, false on failure.
     */
    fun attach(audioSessionId: Int): Boolean {
        if (isAttached) {
            FileLogger.w(TAG, "Already attached — release first")
            return false
        }

        try {
            // Build DynamicsProcessing config:
            // - 2 channels (stereo)
            // - Pre-EQ enabled with BAND_COUNT bands
            // - No MBC (multi-band compressor) — we only want EQ
            // - No post-EQ
            // - No limiter
            val config = DynamicsProcessing.Config.Builder(
                /* variant */          DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                /* channelCount */     2,
                /* preEqInUse */       true,
                /* preEqBandCount */   BAND_COUNT,
                /* mbcInUse */         false,
                /* mbcBandCount */     0,
                /* postEqInUse */      false,
                /* postEqBandCount */  0,
                /* limiterInUse */     false
            ).build()

            dynamicsProcessing = DynamicsProcessing(
                /* priority */       0,
                /* audioSessionId */ audioSessionId,
                /* config */         config
            )

            // Apply initial EQ curves
            applyEqCurves(currentIntensity)

            isAttached = true
            FileLogger.i(TAG, "Attached to session $audioSessionId")
            return true
        } catch (e: Exception) {
            FileLogger.e(TAG, "Failed to attach DynamicsProcessing", e)
            dynamicsProcessing = null
            return false
        }
    }

    /**
     * Set the surround widening intensity.
     *
     * @param intensity 0.0 = flat (no widening), 1.0 = maximum stereo separation.
     *                  Values are clamped to [0.0, 1.0].
     */
    fun setIntensity(intensity: Float) {
        currentIntensity = intensity.coerceIn(0f, 1f)
        if (isAttached) {
            applyEqCurves(currentIntensity)
        }
    }

    /** Get the current intensity value. */
    fun getIntensity(): Float = currentIntensity

    /**
     * Release the DynamicsProcessing effect and free resources.
     * Must be called when playback stops or the service is destroyed.
     */
    fun release() {
        try {
            dynamicsProcessing?.enabled = false
            dynamicsProcessing?.release()
        } catch (e: Exception) {
            FileLogger.w(TAG, "Error releasing DynamicsProcessing: ${e.message}")
        }
        dynamicsProcessing = null
        isAttached = false
        FileLogger.i(TAG, "Released")
    }

    // -- internal --

    /**
     * Apply asymmetric EQ curves to L/R channels scaled by [intensity].
     *
     * At intensity=0, all gains are 0 dB (flat).
     * At intensity=1, gains match LEFT_GAINS_MAX / RIGHT_GAINS_MAX.
     */
    private fun applyEqCurves(intensity: Float) {
        val dp = dynamicsProcessing ?: return

        // Build and apply Left channel (channel 0) pre-EQ
        for (bandIdx in 0 until BAND_COUNT) {
            val gain = LEFT_GAINS_MAX[bandIdx] * intensity
            val band = EqBand(
                /* enabled */        true,
                /* cutoffFrequency */ BAND_FREQUENCIES[bandIdx],
                /* gain */           gain
            )
            dp.setPreEqBandByChannelIndex(/* channelIndex */ 0, /* band */ bandIdx, band)
        }

        // Build and apply Right channel (channel 1) pre-EQ
        for (bandIdx in 0 until BAND_COUNT) {
            val gain = RIGHT_GAINS_MAX[bandIdx] * intensity
            val band = EqBand(
                /* enabled */        true,
                /* cutoffFrequency */ BAND_FREQUENCIES[bandIdx],
                /* gain */           gain
            )
            dp.setPreEqBandByChannelIndex(/* channelIndex */ 1, /* band */ bandIdx, band)
        }

        // Enable the pre-EQ stage on both channels
        val leftEq = dp.getPreEqByChannelIndex(0)
        leftEq.isEnabled = true
        dp.setPreEqByChannelIndex(0, leftEq)

        val rightEq = dp.getPreEqByChannelIndex(1)
        rightEq.isEnabled = true
        dp.setPreEqByChannelIndex(1, rightEq)

        FileLogger.d(TAG, "EQ curves applied: intensity=$intensity")
    }
}
