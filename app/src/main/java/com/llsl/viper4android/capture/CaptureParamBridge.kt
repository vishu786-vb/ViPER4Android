package com.llsl.viper4android.capture

import com.llsl.viper4android.audio.ByteArrayParam
import com.llsl.viper4android.audio.EffectDispatcher
import com.llsl.viper4android.audio.ParamEntry
import com.llsl.viper4android.ui.screens.main.MainUiState
import com.llsl.viper4android.utils.FileLogger

/**
 * CaptureParamBridge — in-process singleton that forwards every effect parameter
 * change from ViperService to ViperCaptureService's JNI engine.
 *
 * ViperService already dispatches to ConfigChannel (AIDL) and/or ViperEffect.
 * This bridge adds a third destination: the capture engine running in the same
 * process.  No IPC, no binder — just a direct reference.
 *
 * Usage:
 *   ViperService calls CaptureParamBridge.dispatchXxx() alongside its own dispatch.
 *   ViperCaptureService registers its engine via CaptureParamBridge.setEngine().
 */
object CaptureParamBridge {

    private const val TAG = "CaptureParamBridge"

    @Volatile
    private var engine: ViperCaptureEngine? = null

    /** Called by ViperCaptureService when the engine is ready, cleared on stop. */
    fun setEngine(e: ViperCaptureEngine?) {
        engine = e
        FileLogger.i(TAG, if (e != null) "Engine registered" else "Engine cleared")
    }

    /** Returns true only when capture is running and has a live engine. */
    fun isActive(): Boolean = engine != null

    // ── forward methods — mirror ViperService's dispatch signatures ──────────

    fun dispatchParam(param: Int, value: Int) {
        engine?.setParam(param, value)
    }

    fun dispatchParam(param: Int, val1: Int, val2: Int, val3: Int) {
        engine?.setParam(param, val1, val2, val3)
    }

    fun dispatchParamBytes(param: Int, data: ByteArray) {
        engine?.setParamBytes(param, data)
    }

    fun dispatchParamsBatch(entries: List<ParamEntry>) {
        val e = engine ?: return
        for (entry in entries) {
            when (entry.values.size) {
                1 -> e.setParam(entry.paramId, entry.values[0])
                2 -> e.setParam(entry.paramId, entry.values[0], entry.values[1])
                3 -> e.setParam(entry.paramId, entry.values[0], entry.values[1], entry.values[2])
            }
        }
    }

    fun dispatchEqBands(param: Int, bandsString: String, bandCountParam: Int = 0, bandCount: Int = 0) {
        val e = engine ?: return
        if (bandCountParam != 0) e.setParam(bandCountParam, bandCount)
        val bands = bandsString.split(";").filter { it.isNotBlank() }
        bands.forEachIndexed { index, bandStr ->
            val level = (bandStr.toFloatOrNull() ?: 0f) * 100
            e.setParam(param, index, level.toInt())
        }
    }

    /**
     * Full-state dispatch — called when ViperService dispatches the entire state
     * (e.g. on device switch or preset load).  Uses ViperEffectAdapter so we
     * reuse EffectDispatcher.dispatchFullState() with zero duplication.
     */
    fun dispatchFullState(state: MainUiState, masterEnabled: Boolean) {
        val e = engine ?: return
        val adapter = ViperEffectAdapter(e)
        EffectDispatcher.dispatchFullState(adapter, state, masterEnabled)
    }
}
