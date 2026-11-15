package com.llsl.viper4android.loopback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import com.llsl.viper4android.utils.FileLogger

/**
 * Dynamic AudioPolicy with a pure LOOP_BACK mix.
 *
 * MUST run only inside the Shizuku UserService (shell UID):
 *   - Creating an AudioPolicy requires MODIFY_AUDIO_ROUTING.
 *   - Shell UID gets this via platform.xml — no "pm grant" needed.
 *   - Non-SDK hidden-API restrictions do NOT apply to the shell process.
 *
 * A LOOP_BACK policy silently removes matching media players from the normal
 * render path and routes them into our AudioRecord sink, so the original audio
 * goes silent on the device.  No DynamicsProcessing muting race needed.
 *
 * Usage:
 *   val policy = LoopbackAudioPolicy(context, appUid)
 *   val record = policy.openRecord()   // 48 kHz, stereo, PCM16
 *   record.startRecording()
 *   // ... read PCM ...
 *   policy.close()
 */
internal class LoopbackAudioPolicy(
    private val context: Context,
    /**
     * UID of the app-process client.  Excluded from the loopback rule so
     * the app's own AudioTrack output never feeds back into the capture.
     */
    private val appUid: Int,
) : AutoCloseable {

    private val TAG = "LoopbackAudioPolicy"
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var policy: Any? = null
    private var record: AudioRecord? = null

    /**
     * Register the AudioPolicy and return the AudioRecord loopback sink.
     * Throws on any failure — caller (LoopbackCaptureUserService) will report
     * the error back via ILoopbackCaptureCallback.onError().
     */
    fun openRecord(): AudioRecord {
        check(policy == null) { "Loopback policy is already open" }

        // ---- Build AudioMixingRule ----------------------------------------
        // Match USAGE_MEDIA, USAGE_GAME, USAGE_UNKNOWN; exclude the app UID.
        val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
        val ruleBuilderClass =
            Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")

        val usageMatchRule = ruleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
        val uidExcludeRule = ruleClass.getField("RULE_MATCH_UID").getInt(null)

        val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
        val addRuleMethod = ruleBuilderClass.getMethod(
            "addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType
        )
        listOf(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME,
            AudioAttributes.USAGE_UNKNOWN,
        ).forEach { usage ->
            addRuleMethod.invoke(
                ruleBuilder,
                AudioAttributes.Builder().setUsage(usage).build(),
                usageMatchRule,
            )
        }
        // Exclude the client app's UID to prevent feedback loop
        ruleBuilderClass
            .getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
            .invoke(ruleBuilder, uidExcludeRule, appUid)
        val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

        // ---- Build AudioMix (LOOP_BACK, 48 kHz stereo PCM16) ---------------
        val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
        val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
        val loopBackFlag = mixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)

        val format = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
            .build()

        val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
        mixBuilderClass.getMethod("setFormat", AudioFormat::class.java)
            .invoke(mixBuilder, format)
        mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
            .invoke(mixBuilder, loopBackFlag)
        val mix = requireNotNull(mixBuilderClass.getMethod("build").invoke(mixBuilder))

        // ---- Build and register AudioPolicy ---------------------------------
        val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        val policyBuilderClass =
            Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
        val policyBuilder =
            policyBuilderClass.getConstructor(Context::class.java).newInstance(context)
        policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
        val createdPolicy = policyBuilderClass.getMethod("build").invoke(policyBuilder)

        val regResult = audioManager.javaClass
            .getMethod("registerAudioPolicy", policyClass)
            .invoke(audioManager, createdPolicy) as Int
        check(regResult == AUDIO_POLICY_SUCCESS) {
            "AudioPolicy registration failed with code $regResult"
        }
        FileLogger.i(TAG, "AudioPolicy registered (loopback)")

        // ---- Open AudioRecord sink ------------------------------------------
        return try {
            val createdRecord = createRecordSink(mix, mixClass, createdPolicy, policyClass)
            check(createdRecord.state == AudioRecord.STATE_INITIALIZED) {
                "AudioRecord sink failed to initialize"
            }
            policy = createdPolicy
            record = createdRecord
            FileLogger.i(TAG, "AudioRecord loopback sink ready")
            createdRecord
        } catch (error: Throwable) {
            // Unregister the policy on sink creation failure so we don't leak it
            runCatching {
                audioManager.javaClass
                    .getMethod("unregisterAudioPolicy", policyClass)
                    .invoke(audioManager, createdPolicy)
            }
            throw error
        }
    }

    /**
     * Create the AudioRecord sink tied to the mix.
     *
     * Primary path  — AudioPolicy.createAudioRecordSink(AudioMix): the
     *                 framework builds the record with correct shell attribution.
     * Fallback path — Manual AudioRecord.Builder with REMOTE_SUBMIX source
     *                 and the mix registration tag, plus explicit shell
     *                 attribution (required when the UserService context lacks
     *                 it natively).
     */
    private fun createRecordSink(
        mix: Any,
        mixClass: Class<*>,
        createdPolicy: Any,
        policyClass: Class<*>,
    ): AudioRecord {
        val registration = mixClass.getMethod("getRegistration").invoke(mix) as String
        FileLogger.i(TAG, "Mix registration tag: '$registration'")

        // --- Primary: AudioPolicy.createAudioRecordSink(AudioMix) ---
        runCatching {
            val record = policyClass.getMethod("createAudioRecordSink", mixClass)
                .invoke(createdPolicy, mix) as AudioRecord
            FileLogger.i(TAG, "Sink via createAudioRecordSink OK")
            return record
        }.onFailure {
            FileLogger.w(TAG, "createAudioRecordSink failed: ${it.message} — trying fallback")
        }

        // --- Fallback: manual AudioRecord with addr tag ---
        val attrBuilder = AudioAttributes.Builder()
        attrBuilder.javaClass
            .getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attrBuilder, REMOTE_SUBMIX_SOURCE)
        attrBuilder.javaClass
            .getMethod("addTag", String::class.java)
            .invoke(attrBuilder, "addr=$registration")
        val attrs = attrBuilder.build()

        val inputFormat = AudioFormat.Builder()
            .setSampleRate(SAMPLE_RATE)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
            .build()

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT
        )
        check(minBuf > 0) { "Unsupported AudioRecord format (minBuf=$minBuf)" }

        val recBuilder = AudioRecord.Builder()
            .setAudioFormat(inputFormat)
            .setBufferSizeInBytes(maxOf(minBuf, DEFAULT_SINK_BUFFER_BYTES))
        recBuilder.javaClass
            .getMethod("setAudioAttributes", AudioAttributes::class.java)
            .invoke(recBuilder, attrs)
        FileLogger.i(TAG, "Sink via manual AudioRecord.Builder (fallback)")
        return recBuilder.build()
    }

    override fun close() {
        runCatching { record?.release() }
        record = null
        policy?.let { p ->
            runCatching {
                val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
                audioManager.javaClass
                    .getMethod("unregisterAudioPolicy", policyClass)
                    .invoke(audioManager, p)
                FileLogger.i(TAG, "AudioPolicy unregistered")
            }.onFailure { FileLogger.w(TAG, "unregisterAudioPolicy failed: ${it.message}") }
        }
        policy = null
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        private const val AUDIO_POLICY_SUCCESS = 0
        private const val REMOTE_SUBMIX_SOURCE = 8      // AudioSource.REMOTE_SUBMIX
        private const val DEFAULT_SINK_BUFFER_BYTES = 16_384
    }
}

/**
 * Context extension: reassign the context's attribution tag to
 * "com.android.shell" before creating the AudioPolicy.
 *
 * On some OEM builds the UserService context retains the app's package
 * attribution; AudioFlinger may then reject the LOOP_BACK policy because the
 * calling package isn't shell.  Swapping the attribution to shell fixes this.
 */
internal fun Context.installShellAttribution(): Context {
    return try {
        val method = Context::class.java.getMethod(
            "createAttributionContext", String::class.java
        )
        method.invoke(this, "com.android.shell") as Context
    } catch (_: Exception) {
        this   // Android < 11 doesn't have createAttributionContext — just use this
    }
}
