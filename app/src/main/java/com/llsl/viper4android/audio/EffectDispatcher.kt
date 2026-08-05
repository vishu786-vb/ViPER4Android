package com.llsl.viper4android.audio

import com.llsl.viper4android.R
import com.llsl.viper4android.data.repository.ViperRepository
import com.llsl.viper4android.ui.screens.main.MainUiState
import com.llsl.viper4android.ui.screens.main.loadEffectPrefs
import com.llsl.viper4android.utils.FileLogger
import kotlinx.coroutines.flow.first
import java.util.Locale
import kotlin.math.ln
import kotlin.math.roundToInt

object EffectDispatcher {
    fun fetThresholdToRaw(dB: Int): Int = (dB / -60.0 * 100.0).roundToInt()

    fun fetKneeToRaw(dB: Int): Int = (dB / 60.0 * 100.0).roundToInt()

    fun fetGainToRaw(dB: Int): Int = (dB / 60.0 * 100.0).roundToInt()

    fun fetAttackMsToRaw(ms: Int): Int {
        val timeSec = ms / 1000.0
        val value = (ln(timeSec) + 9.21034) / 7.600903
        return (value * 100.0).roundToInt().coerceIn(0, 200)
    }

    fun fetReleaseMsToRaw(ms: Int): Int {
        val timeSec = ms / 1000.0
        val value = (ln(timeSec) + 5.298317) / 5.991465
        return (value * 100.0).roundToInt().coerceIn(0, 200)
    }

    fun vseExciterToRaw(value: Int): Int = value

    fun fieldSurroundMidImageToRaw(value: Int): Int = value * 10 + 100

    fun fieldSurroundDepthToRaw(value: Int): Int = value * 75 + 200

    fun dynamicSystemStrengthToRaw(value: Int): Int = value * 20 + 100

    fun bassFrequencyToRaw(value: Int): Int = value + 15

    fun fieldSurroundWideningToRaw(value: Int): Int = value * 100

    fun diffSurroundDelayToRaw(ms: Int): Int = ms * 100

    data class BuiltinEqPreset(
        val key: String,
        val nameRes: Int,
        val bands10: String,
        val bands15: String,
        val bands25: String,
        val bands31: String,
    )

    val BUILTIN_EQ_PRESETS: List<BuiltinEqPreset> =
        listOf(
            BuiltinEqPreset(
                key = "eq_preset_acoustic",
                nameRes = R.string.eq_preset_acoustic,
                bands10 = "4.5;4.5;3.5;1.2;1.0;0.5;1.4;1.75;3.5;2.5;",
                bands15 = "4.5;4.5;4.5;4.0;2.5;1.0;1.0;1.0;0.5;1.0;1.5;2.0;3.0;3.0;2.5;",
                bands25 = "4.5;4.5;4.5;4.5;4.0;4.0;3.5;2.5;1.0;1.0;1.0;1.0;0.5;0.5;1.0;1.0;1.5;1.5;2.0;2.5;3.5;3.0;3.0;2.5;2.5;",
                bands31 = "4.5;4.5;4.5;4.5;4.5;4.5;4.0;4.0;3.5;2.5;2.0;1.0;1.0;1.0;1.0;1.0;0.5;0.5;1.0;1.0;1.5;1.5;1.5;2.0;2.5;3.0;3.5;3.0;3.0;2.5;2.5;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_bass_booster",
                nameRes = R.string.eq_preset_bass_booster,
                bands10 = "6.0;4.0;2.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands15 = "6.0;5.5;4.0;2.5;1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands25 = "6.0;6.0;5.5;4.5;3.5;2.5;2.0;1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands31 = "6.0;6.0;6.0;5.5;4.5;4.0;3.5;2.5;2.0;1.5;0.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_bass_reducer",
                nameRes = R.string.eq_preset_bass_reducer,
                bands10 = "-6.0;-4.0;-2.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands15 = "-6.0;-5.5;-4.0;-2.5;-1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands25 = "-6.0;-6.0;-5.5;-4.5;-3.5;-2.5;-2.0;-1.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands31 = "-6.0;-6.0;-6.0;-5.5;-4.5;-4.0;-3.5;-2.5;-2.0;-1.5;-0.5;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_classical",
                nameRes = R.string.eq_preset_classical,
                bands10 = "0.0;0.0;0.0;0.0;0.0;0.0;-3.0;-3.0;-3.0;-5.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-2.0;-3.0;-3.0;-3.0;-3.5;-5.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-1.0;-2.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.5;-4.5;-5.0;-5.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-1.0;-2.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.0;-3.5;-4.5;-5.0;-5.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_deep",
                nameRes = R.string.eq_preset_deep,
                bands10 = "3.0;2.0;1.0;0.5;0.5;0.0;-1.0;-2.0;-3.0;-3.5;",
                bands15 = "3.0;2.5;2.0;1.5;1.0;0.5;0.5;0.5;0.0;-0.5;-1.5;-2.0;-2.5;-3.0;-3.5;",
                bands25 = "3.0;3.0;2.5;2.5;1.5;1.5;1.0;1.0;0.5;0.5;0.5;0.5;0.0;0.0;-0.5;-0.5;-1.5;-1.5;-2.0;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
                bands31 = "3.0;3.0;3.0;2.5;2.5;2.0;1.5;1.5;1.0;1.0;0.5;0.5;0.5;0.5;0.5;0.5;0.0;0.0;-0.5;-0.5;-1.0;-1.5;-1.5;-2.0;-2.5;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_flat",
                nameRes = R.string.eq_preset_flat,
                bands10 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_rnb",
                nameRes = R.string.eq_preset_rnb,
                bands10 = "3.0;6.0;4.0;1.0;-1.0;-0.5;1.0;1.5;2.5;3.0;",
                bands15 = "3.0;4.0;6.0;4.5;3.0;1.0;-0.5;-1.0;-0.5;0.5;1.0;1.5;2.0;2.5;3.0;",
                bands25 = "3.0;3.0;4.0;5.0;5.5;4.5;4.0;3.0;1.0;0.5;-0.5;-1.0;-0.5;-0.5;0.0;0.5;1.0;1.5;1.5;2.0;2.5;2.5;3.0;3.0;3.0;",
                bands31 = "3.0;3.0;3.0;4.0;5.0;6.0;5.5;4.5;4.0;3.0;2.0;1.0;0.5;-0.5;-1.0;-1.0;-0.5;-0.5;0.0;0.5;1.0;1.0;1.5;1.5;2.0;2.0;2.5;2.5;3.0;3.0;3.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_rock",
                nameRes = R.string.eq_preset_rock,
                bands10 = "4.0;3.0;1.0;0.0;-0.5;0.0;1.5;2.5;3.5;4.0;",
                bands15 = "4.0;3.5;3.0;1.5;0.5;0.0;-0.5;-0.5;0.0;1.0;2.0;2.5;3.0;3.5;4.0;",
                bands25 = "4.0;4.0;3.5;3.5;2.5;1.5;1.0;0.5;0.0;0.0;-0.5;-0.5;0.0;0.0;0.5;1.0;2.0;2.0;2.5;3.0;3.5;3.5;4.0;4.0;4.0;",
                bands31 = "4.0;4.0;4.0;3.5;3.5;3.0;2.5;1.5;1.0;0.5;0.5;0.0;0.0;-0.5;-0.5;-0.5;0.0;0.0;0.5;1.0;1.5;2.0;2.0;2.5;3.0;3.0;3.5;3.5;4.0;4.0;4.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_small_speakers",
                nameRes = R.string.eq_preset_small_speakers,
                bands10 = "3.0;2.0;1.5;1.0;0.5;-0.5;-1.5;-2.0;-3.0;-3.5;",
                bands15 = "3.0;2.5;2.0;1.5;1.5;1.0;0.5;0.0;-0.5;-1.0;-1.5;-2.0;-2.5;-3.0;-3.5;",
                bands25 = "3.0;3.0;2.5;2.5;2.0;1.5;1.5;1.5;1.0;1.0;0.5;0.5;0.0;-0.5;-1.0;-1.0;-1.5;-2.0;-2.0;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
                bands31 = "3.0;3.0;3.0;2.5;2.5;2.0;2.0;1.5;1.5;1.5;1.0;1.0;1.0;0.5;0.5;0.0;0.0;-0.5;-1.0;-1.0;-1.5;-1.5;-2.0;-2.0;-2.5;-2.5;-3.0;-3.0;-3.5;-3.5;-3.5;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_treble_booster",
                nameRes = R.string.eq_preset_treble_booster,
                bands10 = "0.0;0.0;0.0;0.0;0.0;1.0;2.0;3.0;4.0;5.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.5;1.0;1.5;2.5;3.0;3.5;4.5;5.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.5;1.0;1.5;1.5;2.5;2.5;3.0;3.5;4.0;4.5;4.5;5.0;5.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.5;0.5;1.0;1.5;1.5;2.0;2.5;2.5;3.0;3.5;3.5;4.0;4.5;4.5;5.0;5.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_treble_reducer",
                nameRes = R.string.eq_preset_treble_reducer,
                bands10 = "0.0;0.0;0.0;0.0;0.0;-1.0;-2.0;-3.0;-4.0;-5.0;",
                bands15 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;-0.5;-1.0;-1.5;-2.5;-3.0;-3.5;-4.5;-5.0;",
                bands25 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-0.5;-1.0;-1.5;-1.5;-2.5;-2.5;-3.0;-3.5;-4.0;-4.5;-4.5;-5.0;-5.0;",
                bands31 = "0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;0.0;-0.5;-0.5;-1.0;-1.5;-1.5;-2.0;-2.5;-2.5;-3.0;-3.5;-3.5;-4.0;-4.5;-4.5;-5.0;-5.0;",
            ),
            BuiltinEqPreset(
                key = "eq_preset_vocal_booster",
                nameRes = R.string.eq_preset_vocal_booster,
                bands10 = "-1.0;-0.5;0.0;1.5;3.0;3.0;2.0;1.0;0.0;-1.0;",
                bands15 = "-1.0;-1.0;-0.5;0.0;0.5;1.5;2.5;3.0;3.0;2.5;1.5;1.0;0.5;-0.5;-1.0;",
                bands25 = "-1.0;-1.0;-1.0;-0.5;-0.5;0.0;0.0;0.5;1.5;2.0;2.5;3.0;3.0;3.0;2.5;2.5;1.5;1.5;1.0;0.5;0.0;-0.5;-0.5;-1.0;-1.0;",
                bands31 = "-1.0;-1.0;-1.0;-1.0;-0.5;-0.5;-0.5;0.0;0.0;0.5;1.0;1.5;2.0;2.5;3.0;3.0;3.0;3.0;2.5;2.5;2.0;1.5;1.5;1.0;0.5;0.5;0.0;-0.5;-0.5;-1.0;-1.0;",
            ),
        )

    val EQ_PRESET_NAME_RES: Map<String, Int> =
        BUILTIN_EQ_PRESETS.associate { it.key to it.nameRes }

    data class BuiltinDsPreset(
        val key: String,
        val nameRes: Int,
        val xLow: Int,
        val xHigh: Int,
        val yLow: Int,
        val yHigh: Int,
        val sideGainLow: Int,
        val sideGainHigh: Int,
    )

    val BUILTIN_DS_PRESETS: List<BuiltinDsPreset> =
        listOf(
            BuiltinDsPreset(
                key = "ds_device_extreme_headphone_v2",
                nameRes = R.string.ds_device_extreme_headphone_v2,
                xLow = 140,
                xHigh = 6200,
                yLow = 40,
                yHigh = 60,
                sideGainLow = 10,
                sideGainHigh = 80,
            ),
            BuiltinDsPreset(
                key = "ds_device_high_end_headphone_v2",
                nameRes = R.string.ds_device_high_end_headphone_v2,
                xLow = 180,
                xHigh = 5800,
                yLow = 55,
                yHigh = 80,
                sideGainLow = 10,
                sideGainHigh = 70,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_headphone_v2",
                nameRes = R.string.ds_device_common_headphone_v2,
                xLow = 300,
                xHigh = 5600,
                yLow = 60,
                yHigh = 105,
                sideGainLow = 10,
                sideGainHigh = 50,
            ),
            BuiltinDsPreset(
                key = "ds_device_low_end_headphone_v2",
                nameRes = R.string.ds_device_low_end_headphone_v2,
                xLow = 600,
                xHigh = 5400,
                yLow = 60,
                yHigh = 105,
                sideGainLow = 10,
                sideGainHigh = 20,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_earphone_v2",
                nameRes = R.string.ds_device_common_earphone_v2,
                xLow = 100,
                xHigh = 5600,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 50,
                sideGainHigh = 50,
            ),
            BuiltinDsPreset(
                key = "ds_device_extreme_headphone_v1",
                nameRes = R.string.ds_device_extreme_headphone_v1,
                xLow = 1200,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 0,
                sideGainHigh = 20,
            ),
            BuiltinDsPreset(
                key = "ds_device_high_end_headphone_v1",
                nameRes = R.string.ds_device_high_end_headphone_v1,
                xLow = 1000,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 0,
                sideGainHigh = 10,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_headphone_v1",
                nameRes = R.string.ds_device_common_headphone_v1,
                xLow = 800,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 10,
                sideGainHigh = 0,
            ),
            BuiltinDsPreset(
                key = "ds_device_common_earphone_v1",
                nameRes = R.string.ds_device_common_earphone_v1,
                xLow = 400,
                xHigh = 6200,
                yLow = 40,
                yHigh = 80,
                sideGainLow = 10,
                sideGainHigh = 0,
            ),
        )

    val DS_PRESET_NAME_RES: Map<String, Int> =
        BUILTIN_DS_PRESETS.associate { it.key to it.nameRes }

    val EQ_BAND_LABELS_10 =
        listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
    val EQ_BAND_LABELS_15 =
        listOf(
            "25Hz",
            "40Hz",
            "63Hz",
            "100Hz",
            "160Hz",
            "250Hz",
            "400Hz",
            "630Hz",
            "1kHz",
            "1.6kHz",
            "2.5kHz",
            "4kHz",
            "6.3kHz",
            "10kHz",
            "16kHz",
        )
    val EQ_BAND_LABELS_25 =
        listOf(
            "20Hz",
            "31Hz",
            "40Hz",
            "50Hz",
            "80Hz",
            "100Hz",
            "125Hz",
            "160Hz",
            "250Hz",
            "315Hz",
            "400Hz",
            "500Hz",
            "800Hz",
            "1kHz",
            "1.25kHz",
            "1.6kHz",
            "2.5kHz",
            "3.15kHz",
            "4kHz",
            "5kHz",
            "8kHz",
            "10kHz",
            "12.5kHz",
            "16kHz",
            "20kHz",
        )
    val EQ_BAND_LABELS_31 =
        listOf(
            "20Hz",
            "25Hz",
            "31Hz",
            "40Hz",
            "50Hz",
            "63Hz",
            "80Hz",
            "100Hz",
            "125Hz",
            "160Hz",
            "200Hz",
            "250Hz",
            "315Hz",
            "400Hz",
            "500Hz",
            "630Hz",
            "800Hz",
            "1kHz",
            "1.25kHz",
            "1.6kHz",
            "2kHz",
            "2.5kHz",
            "3.15kHz",
            "4kHz",
            "5kHz",
            "6.3kHz",
            "8kHz",
            "10kHz",
            "12.5kHz",
            "16kHz",
            "20kHz",
        )

    fun eqBandLabelsForCount(count: Int): List<String> =
        when (count) {
            15 -> EQ_BAND_LABELS_15
            25 -> EQ_BAND_LABELS_25
            31 -> EQ_BAND_LABELS_31
            else -> EQ_BAND_LABELS_10
        }

    private fun ensureBandCount(
        rawBands: String,
        expectedCount: Int,
    ): String {
        val actualCount = rawBands.split(";").count { it.isNotBlank() }
        return if (actualCount != expectedCount) {
            List(expectedCount) { 0f }.joinToString(";") {
                String.format(Locale.US, "%.1f", it)
            } + ";"
        } else {
            rawBands
        }
    }

    val EQ_GRAPH_LABELS_10 = listOf("31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    val EQ_GRAPH_LABELS_15 =
        listOf(
            "25",
            "40",
            "63",
            "100",
            "160",
            "250",
            "400",
            "630",
            "1k",
            "1.6k",
            "2.5k",
            "4k",
            "6.3k",
            "10k",
            "16k",
        )
    val EQ_GRAPH_LABELS_25 =
        listOf(
            "20",
            "31",
            "40",
            "50",
            "80",
            "100",
            "125",
            "160",
            "250",
            "315",
            "400",
            "500",
            "800",
            "1k",
            "1.25k",
            "1.6k",
            "2.5k",
            "3.15k",
            "4k",
            "5k",
            "8k",
            "10k",
            "12.5k",
            "16k",
            "20k",
        )
    val EQ_GRAPH_LABELS_31 =
        listOf(
            "20",
            "25",
            "31",
            "40",
            "50",
            "63",
            "80",
            "100",
            "125",
            "160",
            "200",
            "250",
            "315",
            "400",
            "500",
            "630",
            "800",
            "1k",
            "1.25k",
            "1.6k",
            "2k",
            "2.5k",
            "3.15k",
            "4k",
            "5k",
            "6.3k",
            "8k",
            "10k",
            "12.5k",
            "16k",
            "20k",
        )

    fun eqGraphLabelsForCount(count: Int): List<String> =
        when (count) {
            15 -> EQ_GRAPH_LABELS_15
            25 -> EQ_GRAPH_LABELS_25
            31 -> EQ_GRAPH_LABELS_31
            else -> EQ_GRAPH_LABELS_10
        }

    fun dispatchFullState(
        effect: ViperEffect,
        state: MainUiState,
        masterEnabled: Boolean,
    ) {
        val mode = if (state.fxType == ViperParams.FX_TYPE_HEADPHONE) "Headphone" else "Speaker"
        FileLogger.d(
            "Dispatch",
            "Dispatch: fullState mode=$mode master=${if (masterEnabled) "ON" else "OFF"}",
        )
        if (state.fxType == ViperParams.FX_TYPE_HEADPHONE) {
            dispatchHeadphoneState(effect, state)
        } else {
            dispatchSpeakerState(effect, state)
        }
    }

    fun dispatchHeadphoneState(
        effect: ViperEffect,
        state: MainUiState,
    ) {
        // Output
        effect.setParameter(ViperParams.PARAM_HP_OUTPUT_VOLUME, state.out.hp.volume)
        effect.setParameter(ViperParams.PARAM_HP_CHANNEL_PAN, state.out.hp.channelPan)
        effect.setParameter(ViperParams.PARAM_HP_LIMITER, state.out.hp.limiter)

        // AGC
        effect.setParameter(ViperParams.PARAM_HP_AGC_ENABLE, if (state.agc.hp.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_HP_AGC_RATIO, state.agc.hp.strength)
        effect.setParameter(ViperParams.PARAM_HP_AGC_MAX_SCALER, state.agc.hp.maxGain)
        effect.setParameter(ViperParams.PARAM_HP_AGC_VOLUME, state.agc.hp.outputThreshold)

        // LUFS
        effect.setParameter(ViperParams.PARAM_HP_LUFS_ENABLE, if (state.lufs.hp.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_HP_LUFS_TARGET, state.lufs.hp.target)
        effect.setParameter(ViperParams.PARAM_HP_LUFS_MAX_GAIN, state.lufs.hp.maxGain)
        effect.setParameter(ViperParams.PARAM_HP_LUFS_SPEED, state.lufs.hp.speed)

        // FET Compressor
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_ENABLE,
            if (state.fet.hp.enabled) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_THRESHOLD,
            fetThresholdToRaw(state.fet.hp.threshold),
        )
        effect.setParameter(ViperParams.PARAM_HP_FET_COMPRESSOR_RATIO, state.fet.hp.ratio)
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_AUTO_KNEE,
            if (state.fet.hp.autoKnee) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_KNEE,
            fetKneeToRaw(state.fet.hp.knee),
        )
        effect.setParameter(ViperParams.PARAM_HP_FET_COMPRESSOR_KNEE_MULTI, state.fet.hp.kneeMulti)
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_AUTO_GAIN,
            if (state.fet.hp.autoGain) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_GAIN,
            fetGainToRaw(state.fet.hp.gain),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_AUTO_ATTACK,
            if (state.fet.hp.autoAttack) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_ATTACK,
            fetAttackMsToRaw(state.fet.hp.attack),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_MAX_ATTACK,
            fetAttackMsToRaw(state.fet.hp.maxAttack),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_AUTO_RELEASE,
            if (state.fet.hp.autoRelease) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_RELEASE,
            fetReleaseMsToRaw(state.fet.hp.release),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_MAX_RELEASE,
            fetReleaseMsToRaw(state.fet.hp.maxRelease),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_CREST,
            fetReleaseMsToRaw(state.fet.hp.crest),
        )
        effect.setParameter(ViperParams.PARAM_HP_FET_COMPRESSOR_ADAPT, state.fet.hp.adapt)
        effect.setParameter(
            ViperParams.PARAM_HP_FET_COMPRESSOR_NO_CLIP,
            if (state.fet.hp.noClip) 100 else 0,
        )

        // Multiband Compressor
        effect.setParameter(
            ViperParams.PARAM_HP_MULTIBAND_COMP_ENABLE,
            if (state.mbc.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_COUNT, 5)
        val hpMbcCrossoverDefaults = intArrayOf(120, 500, 4000, 8000)
        val hpMbcCrossovers =
            state.mbc.hp.crossovers
                .split(";")
                .mapIndexed { i, v -> v.toIntOrNull() ?: hpMbcCrossoverDefaults.getOrElse(i) { 0 } }
        for (i in hpMbcCrossoverDefaults.indices) {
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_CROSSOVER_FREQ,
                i,
                hpMbcCrossovers.getOrElse(i) { hpMbcCrossoverDefaults[i] },
            )
        }
        val hpMbcBandEnables =
            state.mbc.hp.bandEnables
                .split(";")
                .map { (it.toIntOrNull() ?: 1) != 0 }
        for (b in 0 until 5) {
            val bandEnabled = hpMbcBandEnables.getOrElse(b) { true }
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_ENABLE,
                b,
                if (bandEnabled) 100 else 0,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_THRESHOLD,
                b,
                fetThresholdToRaw(
                    state.mbc.hp.thresholds
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: -18,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_RATIO,
                b,
                state.mbc.hp.ratios
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 50,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_GAIN,
                b,
                fetGainToRaw(
                    state.mbc.hp.gains
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 24,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_ATTACK,
                b,
                fetAttackMsToRaw(
                    state.mbc.hp.attacks
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 1,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_RELEASE,
                b,
                fetReleaseMsToRaw(
                    state.mbc.hp.releases
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 100,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_KNEE,
                b,
                fetKneeToRaw(
                    state.mbc.hp.knees
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 0,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_AUTO_GAIN,
                b,
                if ((
                        state.mbc.hp.autoGains
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_AUTO_ATTACK,
                b,
                if ((
                        state.mbc.hp.autoAttacks
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_AUTO_RELEASE,
                b,
                if ((
                        state.mbc.hp.autoReleases
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_AUTO_KNEE,
                b,
                if ((
                        state.mbc.hp.autoKnees
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_KNEE_MULTI,
                b,
                state.mbc.hp.kneeMultis
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 0,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_MAX_ATTACK,
                b,
                fetAttackMsToRaw(
                    state.mbc.hp.maxAttacks
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 44,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_MAX_RELEASE,
                b,
                fetReleaseMsToRaw(
                    state.mbc.hp.maxReleases
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 200,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_CREST,
                b,
                fetReleaseMsToRaw(
                    state.mbc.hp.crests
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 100,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_ADAPT,
                b,
                state.mbc.hp.adapts
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 50,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_MULTIBAND_COMP_BAND_NO_CLIP,
                b,
                if ((
                        state.mbc.hp.noClips
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
        }

        // DDC
        effect.setParameter(ViperParams.PARAM_HP_DDC_ENABLE, if (state.ddc.hp.enabled) 1 else 0)

        // Spectrum Extension
        effect.setParameter(
            ViperParams.PARAM_HP_SPECTRUM_EXTENSION_ENABLE,
            if (state.vse.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_SPECTRUM_EXTENSION_BARK, state.vse.hp.strength)
        effect.setParameter(
            ViperParams.PARAM_HP_SPECTRUM_EXTENSION_BARK_RECONSTRUCT,
            vseExciterToRaw(state.vse.hp.exciter),
        )

        // EQ
        effect.setParameter(ViperParams.PARAM_HP_EQ_BAND_COUNT, state.eq.hp.bandCount)
        effect.setParameter(ViperParams.PARAM_HP_EQ_ENABLE, if (state.eq.hp.enabled) 1 else 0)
        dispatchEqBands(effect, ViperParams.PARAM_HP_EQ_BAND_LEVEL, state.eq.hp.bands)

        // Dynamic EQ
        effect.setParameter(
            ViperParams.PARAM_HP_DYNAMIC_EQ_ENABLE,
            if (state.dynamicEq.hp.enabled) 1 else 0,
        )
        for (b in 0 until state.dynamicEq.hp.bandCount) {
            effect.setParameter(
                ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_FREQ,
                b,
                state.dynamicEq.hp.freqs
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 1000,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_Q,
                b,
                state.dynamicEq.hp.qs
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 150,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_GAIN,
                b,
                state.dynamicEq.hp.gains
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 0,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_THRESHOLD,
                b,
                state.dynamicEq.hp.thresholds
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: -300,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_ATTACK,
                b,
                state.dynamicEq.hp.attacks
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 10,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_RELEASE,
                b,
                state.dynamicEq.hp.releases
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 100,
            )
            effect.setParameter(
                ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_FILTER_TYPE,
                b,
                state.dynamicEq.hp.filterTypes
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 0,
            )
        }
        effect.setParameter(
            ViperParams.PARAM_HP_DYNAMIC_EQ_BAND_COUNT,
            state.dynamicEq.hp.bandCount,
        )

        // Convolver
        effect.setParameter(
            ViperParams.PARAM_HP_CONVOLVER_ENABLE,
            if (state.convolver.hp.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_CONVOLVER_CROSS_CHANNEL,
            state.convolver.hp.crossChannel,
        )

        // Field Surround
        effect.setParameter(
            ViperParams.PARAM_HP_FIELD_SURROUND_ENABLE,
            if (state.fieldSurround.hp.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FIELD_SURROUND_WIDENING,
            fieldSurroundWideningToRaw(state.fieldSurround.hp.widening),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FIELD_SURROUND_MID_IMAGE,
            fieldSurroundMidImageToRaw(state.fieldSurround.hp.midImage),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_FIELD_SURROUND_DEPTH,
            fieldSurroundDepthToRaw(state.fieldSurround.hp.depth),
        )

        // Diff Surround
        effect.setParameter(
            ViperParams.PARAM_HP_DIFF_SURROUND_ENABLE,
            if (state.diffSurround.hp.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_DIFF_SURROUND_DELAY,
            diffSurroundDelayToRaw(state.diffSurround.hp.delay),
        )
        effect.setParameter(
            ViperParams.PARAM_HP_DIFF_SURROUND_REVERSE,
            if (state.diffSurround.hp.reverse) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_DIFF_SURROUND_WET_DRY_MIX,
            state.diffSurround.hp.wetDryMix,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_DIFF_SURROUND_LP_CUTOFF,
            state.diffSurround.hp.lpCutoff,
        )

        // Stereo Imager
        effect.setParameter(
            ViperParams.PARAM_HP_STEREO_IMAGER_ENABLE,
            if (state.stereoImg.hp.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_STEREO_IMAGER_LOW_WIDTH,
            state.stereoImg.hp.lowWidth,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_STEREO_IMAGER_MID_WIDTH,
            state.stereoImg.hp.midWidth,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_STEREO_IMAGER_HIGH_WIDTH,
            state.stereoImg.hp.highWidth,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_STEREO_IMAGER_LOW_CROSSOVER,
            state.stereoImg.hp.lowCrossover,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_STEREO_IMAGER_HIGH_CROSSOVER,
            state.stereoImg.hp.highCrossover,
        )

        // Headphone Surround
        effect.setParameter(
            ViperParams.PARAM_HP_HEADPHONE_SURROUND_ENABLE,
            if (state.vhe.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_HEADPHONE_SURROUND_STRENGTH, state.vhe.hp.quality)

        // Reverb
        effect.setParameter(
            ViperParams.PARAM_HP_REVERB_ENABLE,
            if (state.reverb.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_REVERB_ROOM_SIZE, state.reverb.hp.roomSize * 10)
        effect.setParameter(ViperParams.PARAM_HP_REVERB_ROOM_WIDTH, state.reverb.hp.width * 10)
        effect.setParameter(ViperParams.PARAM_HP_REVERB_ROOM_DAMPENING, state.reverb.hp.dampening)
        effect.setParameter(ViperParams.PARAM_HP_REVERB_ROOM_WET_SIGNAL, state.reverb.hp.wet)
        effect.setParameter(ViperParams.PARAM_HP_REVERB_ROOM_DRY_SIGNAL, state.reverb.hp.dry)

        // Dynamic System
        dispatchDynamicSystem(
            effect,
            state.dynamicSystem.hp.enabled,
            state.dynamicSystem.hp.device,
            state.dynamicSystem.hp.strength,
            ViperParams.PARAM_HP_DYNAMIC_SYSTEM_ENABLE,
            ViperParams.PARAM_HP_DYNAMIC_SYSTEM_STRENGTH,
            ViperParams.PARAM_HP_DYNAMIC_SYSTEM_X_COEFFICIENTS,
            ViperParams.PARAM_HP_DYNAMIC_SYSTEM_Y_COEFFICIENTS,
            ViperParams.PARAM_HP_DYNAMIC_SYSTEM_SIDE_GAIN,
        )

        // Tube Simulator
        effect.setParameter(
            ViperParams.PARAM_HP_TUBE_SIMULATOR_ENABLE,
            if (state.tube.hp.enabled) 1 else 0,
        )

        // Psycho Bass
        effect.setParameter(
            ViperParams.PARAM_HP_PSYCHO_BASS_ENABLE,
            if (state.psychoBass.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_PSYCHO_BASS_CUTOFF, state.psychoBass.hp.cutoff)
        effect.setParameter(
            ViperParams.PARAM_HP_PSYCHO_BASS_INTENSITY,
            state.psychoBass.hp.intensity,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_PSYCHO_BASS_HARMONIC_ORDER,
            state.psychoBass.hp.harmonicOrder,
        )
        effect.setParameter(
            ViperParams.PARAM_HP_PSYCHO_BASS_ORIGINAL_LEVEL,
            state.psychoBass.hp.originalLevel,
        )

        // Bass
        effect.setParameter(ViperParams.PARAM_HP_BASS_ENABLE, if (state.bass.hp.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_HP_BASS_MODE, state.bass.hp.mode)
        effect.setParameter(
            ViperParams.PARAM_HP_BASS_FREQUENCY,
            bassFrequencyToRaw(state.bass.hp.frequency),
        )
        effect.setParameter(ViperParams.PARAM_HP_BASS_GAIN, state.bass.hp.gain)
        effect.setParameter(ViperParams.PARAM_HP_BASS_ANTI_POP, if (state.bass.hp.antiPop) 1 else 0)

        // Bass Mono
        effect.setParameter(
            ViperParams.PARAM_HP_BASS_MONO_ENABLE,
            if (state.bassMono.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_BASS_MONO_MODE, state.bassMono.hp.mode)
        effect.setParameter(
            ViperParams.PARAM_HP_BASS_MONO_FREQUENCY,
            bassFrequencyToRaw(state.bassMono.hp.frequency),
        )
        effect.setParameter(ViperParams.PARAM_HP_BASS_MONO_GAIN, state.bassMono.hp.gain)
        effect.setParameter(
            ViperParams.PARAM_HP_BASS_MONO_ANTI_POP,
            if (state.bassMono.hp.antiPop) 1 else 0,
        )

        // Clarity
        effect.setParameter(
            ViperParams.PARAM_HP_CLARITY_ENABLE,
            if (state.clarity.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_CLARITY_MODE, state.clarity.hp.mode)
        effect.setParameter(ViperParams.PARAM_HP_CLARITY_GAIN, state.clarity.hp.gain)

        // Cure
        effect.setParameter(ViperParams.PARAM_HP_CURE_ENABLE, if (state.cure.hp.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_HP_CURE_STRENGTH, state.cure.hp.strength)

        // AnalogX
        effect.setParameter(
            ViperParams.PARAM_HP_ANALOGX_ENABLE,
            if (state.analog.hp.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_HP_ANALOGX_MODE, state.analog.hp.mode)
    }

    fun dispatchSpeakerState(
        effect: ViperEffect,
        state: MainUiState,
    ) {
        // Output
        effect.setParameter(ViperParams.PARAM_SPK_OUTPUT_VOLUME, state.out.spk.volume)
        effect.setParameter(ViperParams.PARAM_SPK_CHANNEL_PAN, state.out.spk.channelPan)
        effect.setParameter(ViperParams.PARAM_SPK_LIMITER, state.out.spk.limiter)

        // AGC
        effect.setParameter(ViperParams.PARAM_SPK_AGC_ENABLE, if (state.agc.spk.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_SPK_AGC_RATIO, state.agc.spk.strength)
        effect.setParameter(ViperParams.PARAM_SPK_AGC_MAX_SCALER, state.agc.spk.maxGain)
        effect.setParameter(ViperParams.PARAM_SPK_AGC_VOLUME, state.agc.spk.outputThreshold)

        // LUFS
        effect.setParameter(ViperParams.PARAM_SPK_LUFS_ENABLE, if (state.lufs.spk.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_SPK_LUFS_TARGET, state.lufs.spk.target)
        effect.setParameter(ViperParams.PARAM_SPK_LUFS_MAX_GAIN, state.lufs.spk.maxGain)
        effect.setParameter(ViperParams.PARAM_SPK_LUFS_SPEED, state.lufs.spk.speed)

        // FET Compressor
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_ENABLE,
            if (state.fet.spk.enabled) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_THRESHOLD,
            fetThresholdToRaw(state.fet.spk.threshold),
        )
        effect.setParameter(ViperParams.PARAM_SPK_FET_COMPRESSOR_RATIO, state.fet.spk.ratio)
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_AUTO_KNEE,
            if (state.fet.spk.autoKnee) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_KNEE,
            fetKneeToRaw(state.fet.spk.knee),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_KNEE_MULTI,
            state.fet.spk.kneeMulti,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_AUTO_GAIN,
            if (state.fet.spk.autoGain) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_GAIN,
            fetGainToRaw(state.fet.spk.gain),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_AUTO_ATTACK,
            if (state.fet.spk.autoAttack) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_ATTACK,
            fetAttackMsToRaw(state.fet.spk.attack),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_MAX_ATTACK,
            fetAttackMsToRaw(state.fet.spk.maxAttack),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_AUTO_RELEASE,
            if (state.fet.spk.autoRelease) 100 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_RELEASE,
            fetReleaseMsToRaw(state.fet.spk.release),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_MAX_RELEASE,
            fetReleaseMsToRaw(state.fet.spk.maxRelease),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_CREST,
            fetReleaseMsToRaw(state.fet.spk.crest),
        )
        effect.setParameter(ViperParams.PARAM_SPK_FET_COMPRESSOR_ADAPT, state.fet.spk.adapt)
        effect.setParameter(
            ViperParams.PARAM_SPK_FET_COMPRESSOR_NO_CLIP,
            if (state.fet.spk.noClip) 100 else 0,
        )

        // Multiband Compressor
        effect.setParameter(
            ViperParams.PARAM_SPK_MULTIBAND_COMP_ENABLE,
            if (state.mbc.spk.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_COUNT, 5)
        val spkMbcCrossoverDefaults = intArrayOf(120, 500, 4000, 8000)
        val spkMbcCrossovers =
            state.mbc.spk.crossovers
                .split(";")
                .mapIndexed { i, v -> v.toIntOrNull() ?: spkMbcCrossoverDefaults.getOrElse(i) { 0 } }
        for (i in spkMbcCrossoverDefaults.indices) {
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_CROSSOVER_FREQ,
                i,
                spkMbcCrossovers.getOrElse(i) { spkMbcCrossoverDefaults[i] },
            )
        }
        val spkMbcBandEnables =
            state.mbc.spk.bandEnables
                .split(";")
                .map { (it.toIntOrNull() ?: 1) != 0 }
        for (b in 0 until 5) {
            val bandEnabled = spkMbcBandEnables.getOrElse(b) { true }
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_ENABLE,
                b,
                if (bandEnabled) 100 else 0,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_THRESHOLD,
                b,
                fetThresholdToRaw(
                    state.mbc.spk.thresholds
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: -18,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_RATIO,
                b,
                state.mbc.spk.ratios
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 50,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_GAIN,
                b,
                fetGainToRaw(
                    state.mbc.spk.gains
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 24,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_ATTACK,
                b,
                fetAttackMsToRaw(
                    state.mbc.spk.attacks
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 1,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_RELEASE,
                b,
                fetReleaseMsToRaw(
                    state.mbc.spk.releases
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 100,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_KNEE,
                b,
                fetKneeToRaw(
                    state.mbc.spk.knees
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 0,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_AUTO_GAIN,
                b,
                if ((
                        state.mbc.spk.autoGains
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_AUTO_ATTACK,
                b,
                if ((
                        state.mbc.spk.autoAttacks
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_AUTO_RELEASE,
                b,
                if ((
                        state.mbc.spk.autoReleases
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_AUTO_KNEE,
                b,
                if ((
                        state.mbc.spk.autoKnees
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_KNEE_MULTI,
                b,
                state.mbc.spk.kneeMultis
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 0,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_MAX_ATTACK,
                b,
                fetAttackMsToRaw(
                    state.mbc.spk.maxAttacks
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 44,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_MAX_RELEASE,
                b,
                fetReleaseMsToRaw(
                    state.mbc.spk.maxReleases
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 200,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_CREST,
                b,
                fetReleaseMsToRaw(
                    state.mbc.spk.crests
                        .split(";")
                        .getOrNull(b)
                        ?.toIntOrNull() ?: 100,
                ),
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_ADAPT,
                b,
                state.mbc.spk.adapts
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 50,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_MULTIBAND_COMP_BAND_NO_CLIP,
                b,
                if ((
                        state.mbc.spk.noClips
                            .split(";")
                            .getOrNull(b)
                            ?.toIntOrNull()
                            ?: 1
                    ) != 0
                ) {
                    100
                } else {
                    0
                },
            )
        }

        // DDC
        effect.setParameter(ViperParams.PARAM_SPK_DDC_ENABLE, if (state.ddc.spk.enabled) 1 else 0)

        // Spectrum Extension
        effect.setParameter(
            ViperParams.PARAM_SPK_SPECTRUM_EXTENSION_ENABLE,
            if (state.vse.spk.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_SPK_SPECTRUM_EXTENSION_BARK, state.vse.spk.strength)
        effect.setParameter(
            ViperParams.PARAM_SPK_SPECTRUM_EXTENSION_BARK_RECONSTRUCT,
            vseExciterToRaw(state.vse.spk.exciter),
        )

        // EQ
        effect.setParameter(ViperParams.PARAM_SPK_EQ_BAND_COUNT, state.eq.spk.bandCount)
        effect.setParameter(ViperParams.PARAM_SPK_EQ_ENABLE, if (state.eq.spk.enabled) 1 else 0)
        dispatchEqBands(effect, ViperParams.PARAM_SPK_EQ_BAND_LEVEL, state.eq.spk.bands)

        // Dynamic EQ
        effect.setParameter(
            ViperParams.PARAM_SPK_DYNAMIC_EQ_ENABLE,
            if (state.dynamicEq.spk.enabled) 1 else 0,
        )
        for (b in 0 until state.dynamicEq.spk.bandCount) {
            effect.setParameter(
                ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_FREQ,
                b,
                state.dynamicEq.spk.freqs
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 1000,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_Q,
                b,
                state.dynamicEq.spk.qs
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 150,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_GAIN,
                b,
                state.dynamicEq.spk.gains
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 0,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_THRESHOLD,
                b,
                state.dynamicEq.spk.thresholds
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: -300,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_ATTACK,
                b,
                state.dynamicEq.spk.attacks
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 10,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_RELEASE,
                b,
                state.dynamicEq.spk.releases
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 100,
            )
            effect.setParameter(
                ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_FILTER_TYPE,
                b,
                state.dynamicEq.spk.filterTypes
                    .split(";")
                    .getOrNull(b)
                    ?.toIntOrNull() ?: 0,
            )
        }
        effect.setParameter(
            ViperParams.PARAM_SPK_DYNAMIC_EQ_BAND_COUNT,
            state.dynamicEq.spk.bandCount,
        )

        // Convolver
        effect.setParameter(
            ViperParams.PARAM_SPK_CONVOLVER_ENABLE,
            if (state.convolver.spk.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_CONVOLVER_CROSS_CHANNEL,
            state.convolver.spk.crossChannel,
        )

        // Field Surround
        effect.setParameter(
            ViperParams.PARAM_SPK_FIELD_SURROUND_ENABLE,
            if (state.fieldSurround.spk.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FIELD_SURROUND_WIDENING,
            fieldSurroundWideningToRaw(state.fieldSurround.spk.widening),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FIELD_SURROUND_MID_IMAGE,
            fieldSurroundMidImageToRaw(state.fieldSurround.spk.midImage),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_FIELD_SURROUND_DEPTH,
            fieldSurroundDepthToRaw(state.fieldSurround.spk.depth),
        )

        // Diff Surround
        effect.setParameter(
            ViperParams.PARAM_SPK_DIFF_SURROUND_ENABLE,
            if (state.diffSurround.spk.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_DIFF_SURROUND_DELAY,
            diffSurroundDelayToRaw(state.diffSurround.spk.delay),
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_DIFF_SURROUND_REVERSE,
            if (state.diffSurround.spk.reverse) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_DIFF_SURROUND_WET_DRY_MIX,
            state.diffSurround.spk.wetDryMix,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_DIFF_SURROUND_LP_CUTOFF,
            state.diffSurround.spk.lpCutoff,
        )

        // Stereo Imager
        effect.setParameter(
            ViperParams.PARAM_SPK_STEREO_IMAGER_ENABLE,
            if (state.stereoImg.spk.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_STEREO_IMAGER_LOW_WIDTH,
            state.stereoImg.spk.lowWidth,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_STEREO_IMAGER_MID_WIDTH,
            state.stereoImg.spk.midWidth,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_STEREO_IMAGER_HIGH_WIDTH,
            state.stereoImg.spk.highWidth,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_STEREO_IMAGER_LOW_CROSSOVER,
            state.stereoImg.spk.lowCrossover,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_STEREO_IMAGER_HIGH_CROSSOVER,
            state.stereoImg.spk.highCrossover,
        )

        // Headphone Surround
        effect.setParameter(
            ViperParams.PARAM_SPK_HEADPHONE_SURROUND_ENABLE,
            if (state.vhe.spk.enabled) 1 else 0,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_HEADPHONE_SURROUND_STRENGTH,
            state.vhe.spk.quality,
        )

        // Reverb
        effect.setParameter(
            ViperParams.PARAM_SPK_REVERB_ENABLE,
            if (state.reverb.spk.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_SPK_REVERB_ROOM_SIZE, state.reverb.spk.roomSize * 10)
        effect.setParameter(ViperParams.PARAM_SPK_REVERB_ROOM_WIDTH, state.reverb.spk.width * 10)
        effect.setParameter(ViperParams.PARAM_SPK_REVERB_ROOM_DAMPENING, state.reverb.spk.dampening)
        effect.setParameter(ViperParams.PARAM_SPK_REVERB_ROOM_WET_SIGNAL, state.reverb.spk.wet)
        effect.setParameter(ViperParams.PARAM_SPK_REVERB_ROOM_DRY_SIGNAL, state.reverb.spk.dry)

        // Dynamic System
        dispatchDynamicSystem(
            effect,
            state.dynamicSystem.spk.enabled,
            state.dynamicSystem.spk.device,
            state.dynamicSystem.spk.strength,
            ViperParams.PARAM_SPK_DYNAMIC_SYSTEM_ENABLE,
            ViperParams.PARAM_SPK_DYNAMIC_SYSTEM_STRENGTH,
            ViperParams.PARAM_SPK_DYNAMIC_SYSTEM_X_COEFFICIENTS,
            ViperParams.PARAM_SPK_DYNAMIC_SYSTEM_Y_COEFFICIENTS,
            ViperParams.PARAM_SPK_DYNAMIC_SYSTEM_SIDE_GAIN,
        )

        // Tube Simulator
        effect.setParameter(
            ViperParams.PARAM_SPK_TUBE_SIMULATOR_ENABLE,
            if (state.tube.spk.enabled) 1 else 0,
        )

        // Psycho Bass
        effect.setParameter(
            ViperParams.PARAM_SPK_PSYCHO_BASS_ENABLE,
            if (state.psychoBass.spk.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_SPK_PSYCHO_BASS_CUTOFF, state.psychoBass.spk.cutoff)
        effect.setParameter(
            ViperParams.PARAM_SPK_PSYCHO_BASS_INTENSITY,
            state.psychoBass.spk.intensity,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_PSYCHO_BASS_HARMONIC_ORDER,
            state.psychoBass.spk.harmonicOrder,
        )
        effect.setParameter(
            ViperParams.PARAM_SPK_PSYCHO_BASS_ORIGINAL_LEVEL,
            state.psychoBass.spk.originalLevel,
        )

        // Bass
        effect.setParameter(ViperParams.PARAM_SPK_BASS_ENABLE, if (state.bass.spk.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_SPK_BASS_MODE, state.bass.spk.mode)
        effect.setParameter(
            ViperParams.PARAM_SPK_BASS_FREQUENCY,
            bassFrequencyToRaw(state.bass.spk.frequency),
        )
        effect.setParameter(ViperParams.PARAM_SPK_BASS_GAIN, state.bass.spk.gain)
        effect.setParameter(
            ViperParams.PARAM_SPK_BASS_ANTI_POP,
            if (state.bass.spk.antiPop) 1 else 0,
        )

        // Bass Mono
        effect.setParameter(
            ViperParams.PARAM_SPK_BASS_MONO_ENABLE,
            if (state.bassMono.spk.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_SPK_BASS_MONO_MODE, state.bassMono.spk.mode)
        effect.setParameter(
            ViperParams.PARAM_SPK_BASS_MONO_FREQUENCY,
            bassFrequencyToRaw(state.bassMono.spk.frequency),
        )
        effect.setParameter(ViperParams.PARAM_SPK_BASS_MONO_GAIN, state.bassMono.spk.gain)
        effect.setParameter(
            ViperParams.PARAM_SPK_BASS_MONO_ANTI_POP,
            if (state.bassMono.spk.antiPop) 1 else 0,
        )

        // Clarity
        effect.setParameter(
            ViperParams.PARAM_SPK_CLARITY_ENABLE,
            if (state.clarity.spk.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_SPK_CLARITY_MODE, state.clarity.spk.mode)
        effect.setParameter(ViperParams.PARAM_SPK_CLARITY_GAIN, state.clarity.spk.gain)

        effect.setParameter(ViperParams.PARAM_SPK_CURE_ENABLE, if (state.cure.spk.enabled) 1 else 0)
        effect.setParameter(ViperParams.PARAM_SPK_CURE_STRENGTH, state.cure.spk.strength)

        // AnalogX
        effect.setParameter(
            ViperParams.PARAM_SPK_ANALOGX_ENABLE,
            if (state.analog.spk.enabled) 1 else 0,
        )
        effect.setParameter(ViperParams.PARAM_SPK_ANALOGX_MODE, state.analog.spk.mode)

        // Speaker Correction
        effect.setParameter(
            ViperParams.PARAM_SPK_SPEAKER_CORRECTION_ENABLE,
            if (state.speakerCorrection.spk.enabled) 1 else 0,
        )
    }

    fun dispatchEqBands(
        effect: ViperEffect,
        param: Int,
        bandsString: String,
    ) {
        val bands = bandsString.split(";").filter { it.isNotBlank() }
        for ((index, bandStr) in bands.withIndex()) {
            val level = (bandStr.toFloatOrNull() ?: 0f) * 100
            effect.setParameter(param, index, level.toInt())
        }
    }

    private fun dispatchDynamicSystem(
        effect: ViperEffect,
        enabled: Boolean,
        deviceIndex: Int,
        strength: Int,
        paramEnable: Int,
        paramStrength: Int,
        paramXCoeffs: Int,
        paramYCoeffs: Int,
        paramSideGain: Int,
    ) {
        effect.setParameter(paramEnable, if (enabled) 1 else 0)
        FileLogger.d(
            "Dispatch",
            "DynamicSystem: ${if (enabled) "ON" else "OFF"} device=$deviceIndex strength=$strength",
        )
        effect.setParameter(paramStrength, dynamicSystemStrengthToRaw(strength))
        val preset = BUILTIN_DS_PRESETS.getOrElse(deviceIndex) { BUILTIN_DS_PRESETS[0] }
        effect.setParameter(paramXCoeffs, preset.xLow, preset.xHigh)
        effect.setParameter(paramYCoeffs, preset.yLow, preset.yHigh)
        effect.setParameter(paramSideGain, preset.sideGainLow, preset.sideGainHigh)
    }

    suspend fun loadFullStateFromPrefs(repository: ViperRepository): MainUiState {
        val fxType =
            repository
                .getIntPreference(ViperRepository.PREF_FX_TYPE, ViperParams.FX_TYPE_HEADPHONE)
                .first()
        var s = loadEffectPrefs(repository, isSpk = false)
        s = loadEffectPrefs(repository, isSpk = true, state = s)
        val eqBands = ensureBandCount(s.eq.hp.bands, s.eq.hp.bandCount)
        val spkEqBands = ensureBandCount(s.eq.spk.bands, s.eq.spk.bandCount)
        return s.copy(
            fxType = fxType,
            eq =
                s.eq.copy(
                    hp = s.eq.hp.copy(bands = eqBands),
                    spk = s.eq.spk.copy(bands = spkEqBands),
                ),
        )
    }
}
