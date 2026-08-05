package com.llsl.viper4android.capture

import com.llsl.viper4android.audio.ViperEffect

/**
 * ViperEffectAdapter — wraps [ViperCaptureEngine] behind the [ViperEffect] interface.
 *
 * ViperEffect is now open. We subclass it (session=0, no AudioEffect created) and
 * override every setParameter overload to forward to the JNI engine instead.
 *
 * This lets EffectDispatcher.dispatchFullState() drive the capture engine directly
 * with zero code duplication — the same dispatch code used by ViperService.
 */
class ViperEffectAdapter(private val engine: ViperCaptureEngine) : ViperEffect(0) {

    // create() is NOT called — we never want to create an AudioEffect object.
    // The ViperEffect base constructor stores audioSessionId=0 but touches nothing else.

    override fun setParameter(param: Int, value: Int) {
        engine.setParam(param, value)
    }

    override fun setParameter(param: Int, val1: Int, val2: Int) {
        engine.setParam(param, val1, val2)
    }

    override fun setParameter(param: Int, val1: Int, val2: Int, val3: Int) {
        engine.setParam(param, val1, val2, val3)
    }

    override fun setParameter(param: Int, value: ByteArray) {
        engine.setParamBytes(param, value)
    }

    /** enabled is a no-op — the JNI engine is always on while capture is running */
    override var enabled: Boolean = true
        set(_) { /* intentional no-op */ }
}
