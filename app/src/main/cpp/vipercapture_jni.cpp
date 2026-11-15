#include <jni.h>
#include <android/log.h>
#include <memory>
#include <vector>
#include <mutex>
#include <unordered_map>

// ViPER.h transitively includes ViPERParams.h and all effect headers
#include "viper/ViPER.h"

using namespace viper::params;

#define TAG "ViperCapture"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── param ID translation ──────────────────────────────────────────────────────
//
// The Kotlin layer uses AIDL-style param IDs (HP: 0x101xx–0x102xx,
// SPK: 0x103xx–0x104xx).  The C++ ViPER engine uses a different unified set
// (kParam* constants in ViPERParams.h).  Translate before dispatching.
//
// Rule: HP and SPK IDs for the same effect differ only in the leading hex digit
// (0x101... vs 0x103..., 0x102... vs 0x104...).  We strip the HP/SPK prefix and
// map to the native ID.  HP and SPK share the same physical DSP chain — the
// capture engine always applies effects regardless of device type.

static const std::unordered_map<int, int>& paramMap() {
    static const std::unordered_map<int, int> kMap = {
        // ── Output / Master Limiter ───────────────────────────────────────────
        // PARAM_HP_OUTPUT_VOLUME  = 0x101F0  →  kParamMasterLimiterOutputVolume = 0x10111
        { 0x101F0, kParamMasterLimiterOutputVolume },
        { 0x103F0, kParamMasterLimiterOutputVolume },
        // PARAM_HP_CHANNEL_PAN    = 0x101F1  →  kParamMasterLimiterChannelPan
        { 0x101F1, kParamMasterLimiterChannelPan },
        { 0x103F1, kParamMasterLimiterChannelPan },
        // PARAM_HP_LIMITER        = 0x101F2  →  kParamMasterLimiterThreshold
        { 0x101F2, kParamMasterLimiterThreshold },
        { 0x103F2, kParamMasterLimiterThreshold },

        // ── AGC (PlaybackGainControl) ─────────────────────────────────────────
        { 0x10140, kParamPlaybackGainControlEnable },          // PARAM_HP_AGC_ENABLE
        { 0x10340, kParamPlaybackGainControlEnable },
        { 0x10141, kParamPlaybackGainControlStrength },        // PARAM_HP_AGC_RATIO
        { 0x10341, kParamPlaybackGainControlStrength },
        { 0x10142, kParamPlaybackGainControlOutputThreshold }, // PARAM_HP_AGC_VOLUME
        { 0x10342, kParamPlaybackGainControlOutputThreshold },
        { 0x10143, kParamPlaybackGainControlMaxGain },         // PARAM_HP_AGC_MAX_SCALER
        { 0x10343, kParamPlaybackGainControlMaxGain },

        // ── LUFS ──────────────────────────────────────────────────────────────
        { 0x10270, kParamLufsEnable },   // PARAM_HP_LUFS_ENABLE
        { 0x10470, kParamLufsEnable },
        { 0x10271, kParamLufsTarget },
        { 0x10471, kParamLufsTarget },
        { 0x10272, kParamLufsMaxGain },
        { 0x10472, kParamLufsMaxGain },
        { 0x10273, kParamLufsSpeed },
        { 0x10473, kParamLufsSpeed },

        // ── FET Compressor ────────────────────────────────────────────────────
        { 0x10200, kParamFetCompressorEnable },      // PARAM_HP_FET_COMPRESSOR_ENABLE
        { 0x10400, kParamFetCompressorEnable },
        { 0x10201, kParamFetCompressorThreshold },
        { 0x10401, kParamFetCompressorThreshold },
        { 0x10202, kParamFetCompressorRatio },
        { 0x10402, kParamFetCompressorRatio },
        { 0x10203, kParamFetCompressorKnee },
        { 0x10403, kParamFetCompressorKnee },
        { 0x10204, kParamFetCompressorKneeAuto },
        { 0x10404, kParamFetCompressorKneeAuto },
        { 0x10205, kParamFetCompressorGain },
        { 0x10405, kParamFetCompressorGain },
        { 0x10206, kParamFetCompressorGainAuto },
        { 0x10406, kParamFetCompressorGainAuto },
        { 0x10207, kParamFetCompressorAttack },
        { 0x10407, kParamFetCompressorAttack },
        { 0x10208, kParamFetCompressorAttackAuto },
        { 0x10408, kParamFetCompressorAttackAuto },
        { 0x10209, kParamFetCompressorRelease },
        { 0x10409, kParamFetCompressorRelease },
        { 0x1020A, kParamFetCompressorReleaseAuto },
        { 0x1040A, kParamFetCompressorReleaseAuto },
        { 0x1020B, kParamFetCompressorKneeMulti },
        { 0x1040B, kParamFetCompressorKneeMulti },
        { 0x1020C, kParamFetCompressorMaxAttack },
        { 0x1040C, kParamFetCompressorMaxAttack },
        { 0x1020D, kParamFetCompressorMaxRelease },
        { 0x1040D, kParamFetCompressorMaxRelease },
        { 0x1020E, kParamFetCompressorCrest },
        { 0x1040E, kParamFetCompressorCrest },
        { 0x1020F, kParamFetCompressorAdapt },
        { 0x1040F, kParamFetCompressorAdapt },
        { 0x10210, kParamFetCompressorNoClip },
        { 0x10410, kParamFetCompressorNoClip },

        // ── Bass ──────────────────────────────────────────────────────────────
        { 0x10160, kParamBassEnable },
        { 0x10360, kParamBassEnable },
        { 0x10161, kParamBassMode },
        { 0x10361, kParamBassMode },
        { 0x10162, kParamBassFrequency },
        { 0x10362, kParamBassFrequency },
        { 0x10163, kParamBassGain },
        { 0x10363, kParamBassGain },
        { 0x10168, kParamBassAntiPop },   // PARAM_HP_BASS_ANTI_POP = 0x10168
        { 0x10368, kParamBassAntiPop },

        // ── Bass Mono ─────────────────────────────────────────────────────────
        { 0x10164, kParamBassMonoEnable },
        { 0x10364, kParamBassMonoEnable },
        { 0x10165, kParamBassMonoMode },
        { 0x10365, kParamBassMonoMode },
        { 0x10166, kParamBassMonoFrequency },
        { 0x10366, kParamBassMonoFrequency },
        { 0x10167, kParamBassMonoGain },
        { 0x10367, kParamBassMonoGain },
        { 0x10169, kParamBassMonoAntiPop },
        { 0x10369, kParamBassMonoAntiPop },

        // ── Psychoacoustic Bass ───────────────────────────────────────────────
        { 0x10280, kParamPsychoacousticBassEnable },
        { 0x10480, kParamPsychoacousticBassEnable },
        { 0x10281, kParamPsychoacousticBassCutoff },
        { 0x10481, kParamPsychoacousticBassCutoff },
        { 0x10282, kParamPsychoacousticBassIntensity },
        { 0x10482, kParamPsychoacousticBassIntensity },
        { 0x10283, kParamPsychoacousticBassHarmonicOrder },
        { 0x10483, kParamPsychoacousticBassHarmonicOrder },
        { 0x10284, kParamPsychoacousticBassOriginalLevel },
        { 0x10484, kParamPsychoacousticBassOriginalLevel },

        // ── Spectrum Extension ────────────────────────────────────────────────
        { 0x10190, kParamSpectrumExtensionEnable },
        { 0x10390, kParamSpectrumExtensionEnable },
        { 0x10191, kParamSpectrumExtensionStrength },
        { 0x10391, kParamSpectrumExtensionStrength },
        { 0x10192, kParamSpectrumExtensionExciter },
        { 0x10392, kParamSpectrumExtensionExciter },

        // ── EQ ────────────────────────────────────────────────────────────────
        { 0x10120, kParamEqualizerEnable },
        { 0x10320, kParamEqualizerEnable },
        { 0x10121, kParamEqualizerBandLevel },
        { 0x10321, kParamEqualizerBandLevel },
        { 0x10122, kParamEqualizerBandCount },
        { 0x10322, kParamEqualizerBandCount },

        // ── Convolver ─────────────────────────────────────────────────────────
        { 0x10100, kParamConvolverEnable },
        { 0x10300, kParamConvolverEnable },
        { 0x10101, kParamConvolverSetKernel },
        { 0x10301, kParamConvolverSetKernel },
        { 0x10102, kParamConvolverPrepareBuffer },
        { 0x10302, kParamConvolverPrepareBuffer },
        { 0x10103, kParamConvolverSetBuffer },
        { 0x10303, kParamConvolverSetBuffer },
        { 0x10104, kParamConvolverCommitBuffer },
        { 0x10304, kParamConvolverCommitBuffer },
        { 0x10105, kParamConvolverCrossChannel },
        { 0x10305, kParamConvolverCrossChannel },

        // ── DDC ───────────────────────────────────────────────────────────────
        { 0x10110, kParamDdcEnable },
        { 0x10310, kParamDdcEnable },
        { 0x10111, kParamDdcCoefficients },
        { 0x10311, kParamDdcCoefficients },

        // ── Field Surround ────────────────────────────────────────────────────
        { 0x101A0, kParamFieldSurroundEnable },
        { 0x103A0, kParamFieldSurroundEnable },
        { 0x101A1, kParamFieldSurroundWidening },
        { 0x103A1, kParamFieldSurroundWidening },
        { 0x101A2, kParamFieldSurroundMidImage },
        { 0x103A2, kParamFieldSurroundMidImage },
        { 0x101A3, kParamFieldSurroundDepth },
        { 0x103A3, kParamFieldSurroundDepth },

        // ── Diff Surround ─────────────────────────────────────────────────────
        { 0x101B0, kParamDiffSurroundEnable },
        { 0x103B0, kParamDiffSurroundEnable },
        { 0x101B1, kParamDiffSurroundDelay },
        { 0x103B1, kParamDiffSurroundDelay },
        { 0x101B2, kParamDiffSurroundReverse },
        { 0x103B2, kParamDiffSurroundReverse },
        { 0x101B3, kParamDiffSurroundWetDryMix },
        { 0x103B3, kParamDiffSurroundWetDryMix },
        { 0x101B4, kParamDiffSurroundLpCutoff },
        { 0x103B4, kParamDiffSurroundLpCutoff },

        // ── Stereo Imager ─────────────────────────────────────────────────────
        { 0x10250, kParamStereoImagerEnable },
        { 0x10450, kParamStereoImagerEnable },
        { 0x10251, kParamStereoImagerLowWidth },
        { 0x10451, kParamStereoImagerLowWidth },
        { 0x10252, kParamStereoImagerMidWidth },
        { 0x10452, kParamStereoImagerMidWidth },
        { 0x10253, kParamStereoImagerHighWidth },
        { 0x10453, kParamStereoImagerHighWidth },
        { 0x10254, kParamStereoImagerLowCrossover },
        { 0x10454, kParamStereoImagerLowCrossover },
        { 0x10255, kParamStereoImagerHighCrossover },
        { 0x10455, kParamStereoImagerHighCrossover },

        // ── Headphone Surround (VHE) ──────────────────────────────────────────
        { 0x10180, kParamHeadphoneSurroundEnable },
        { 0x10380, kParamHeadphoneSurroundEnable },
        { 0x10181, kParamHeadphoneSurroundQuality },
        { 0x10381, kParamHeadphoneSurroundQuality },

        // ── Reverb ────────────────────────────────────────────────────────────
        { 0x10130, kParamReverbEnable },
        { 0x10330, kParamReverbEnable },
        { 0x10131, kParamReverbRoomSize },
        { 0x10331, kParamReverbRoomSize },
        { 0x10132, kParamReverbWidth },
        { 0x10332, kParamReverbWidth },
        { 0x10133, kParamReverbDamp },
        { 0x10333, kParamReverbDamp },
        { 0x10134, kParamReverbWet },
        { 0x10334, kParamReverbWet },
        { 0x10135, kParamReverbDry },
        { 0x10335, kParamReverbDry },

        // ── Dynamic System ────────────────────────────────────────────────────
        { 0x10150, kParamDynamicSystemEnable },
        { 0x10350, kParamDynamicSystemEnable },
        { 0x10151, kParamDynamicSystemXCoefficients },
        { 0x10351, kParamDynamicSystemXCoefficients },
        { 0x10152, kParamDynamicSystemYCoefficients },
        { 0x10352, kParamDynamicSystemYCoefficients },
        { 0x10153, kParamDynamicSystemSideGain },
        { 0x10353, kParamDynamicSystemSideGain },
        { 0x10154, kParamDynamicSystemStrength },
        { 0x10354, kParamDynamicSystemStrength },

        // ── Clarity ───────────────────────────────────────────────────────────
        { 0x10170, kParamClarityEnable },
        { 0x10370, kParamClarityEnable },
        { 0x10171, kParamClarityMode },
        { 0x10371, kParamClarityMode },
        { 0x10172, kParamClarityGain },
        { 0x10372, kParamClarityGain },

        // ── Cure (Crossfeed) ──────────────────────────────────────────────────
        { 0x101C0, kParamCureEnable },
        { 0x103C0, kParamCureEnable },
        { 0x101C1, kParamCureCrossfeedPreset },
        { 0x103C1, kParamCureCrossfeedPreset },

        // ── Tube Simulator ────────────────────────────────────────────────────
        { 0x101D0, kParamTubeSimulatorEnable },
        { 0x103D0, kParamTubeSimulatorEnable },

        // ── AnalogX ───────────────────────────────────────────────────────────
        { 0x101E0, kParamAnalogXEnable },
        { 0x103E0, kParamAnalogXEnable },
        { 0x101E1, kParamAnalogXMode },
        { 0x103E1, kParamAnalogXMode },

        // ── Speaker Correction ────────────────────────────────────────────────
        { 0x10420, kParamSpeakerCorrectionEnable },

        // ── Multiband Compressor ──────────────────────────────────────────────
        { 0x10230, kParamMultibandCompressorEnable },
        { 0x10430, kParamMultibandCompressorEnable },
        { 0x10231, kParamMultibandCompressorBandCount },
        { 0x10431, kParamMultibandCompressorBandCount },
        { 0x10232, kParamMultibandCompressorCrossoverFrequency },
        { 0x10432, kParamMultibandCompressorCrossoverFrequency },
        { 0x10233, kParamMultibandCompressorBandThreshold },
        { 0x10433, kParamMultibandCompressorBandThreshold },
        { 0x10234, kParamMultibandCompressorBandRatio },
        { 0x10434, kParamMultibandCompressorBandRatio },
        { 0x10235, kParamMultibandCompressorBandKnee },
        { 0x10435, kParamMultibandCompressorBandKnee },
        { 0x10236, kParamMultibandCompressorBandKneeAuto },
        { 0x10436, kParamMultibandCompressorBandKneeAuto },
        { 0x10237, kParamMultibandCompressorBandGain },
        { 0x10437, kParamMultibandCompressorBandGain },
        { 0x10238, kParamMultibandCompressorBandGainAuto },
        { 0x10438, kParamMultibandCompressorBandGainAuto },
        { 0x10239, kParamMultibandCompressorBandAttack },
        { 0x10439, kParamMultibandCompressorBandAttack },
        { 0x1023A, kParamMultibandCompressorBandAttackAuto },
        { 0x1043A, kParamMultibandCompressorBandAttackAuto },
        { 0x1023B, kParamMultibandCompressorBandRelease },
        { 0x1043B, kParamMultibandCompressorBandRelease },
        { 0x1023C, kParamMultibandCompressorBandReleaseAuto },
        { 0x1043C, kParamMultibandCompressorBandReleaseAuto },
        { 0x1023D, kParamMultibandCompressorBandKneeMulti },
        { 0x1043D, kParamMultibandCompressorBandKneeMulti },
        { 0x1023E, kParamMultibandCompressorBandMaxAttack },
        { 0x1043E, kParamMultibandCompressorBandMaxAttack },
        { 0x1023F, kParamMultibandCompressorBandMaxRelease },
        { 0x1043F, kParamMultibandCompressorBandMaxRelease },
        { 0x10240, kParamMultibandCompressorBandCrest },
        { 0x10440, kParamMultibandCompressorBandCrest },
        { 0x10241, kParamMultibandCompressorBandAdapt },
        { 0x10441, kParamMultibandCompressorBandAdapt },
        { 0x10242, kParamMultibandCompressorBandNoClip },
        { 0x10442, kParamMultibandCompressorBandNoClip },
        { 0x10243, kParamMultibandCompressorBandEnable },
        { 0x10443, kParamMultibandCompressorBandEnable },

        // ── Dynamic EQ ────────────────────────────────────────────────────────
        // Kotlin HP path (0x1026x) and SPK path (0x1046x) both map here.
        // The 0x102Ax entries cover the AudioEffect direct-dispatch path.
        { 0x10260, kParamDynamicEqEnable },
        { 0x10460, kParamDynamicEqEnable },
        { 0x102A0, kParamDynamicEqEnable },
        { 0x10261, kParamDynamicEqBandCount },
        { 0x10461, kParamDynamicEqBandCount },
        { 0x102A1, kParamDynamicEqBandCount },
        { 0x10262, kParamDynamicEqBandFrequency },
        { 0x10462, kParamDynamicEqBandFrequency },
        { 0x102A2, kParamDynamicEqBandFrequency },
        { 0x10263, kParamDynamicEqBandQ },
        { 0x10463, kParamDynamicEqBandQ },
        { 0x102A3, kParamDynamicEqBandQ },
        { 0x10264, kParamDynamicEqBandGain },
        { 0x10464, kParamDynamicEqBandGain },
        { 0x102A4, kParamDynamicEqBandGain },
        { 0x10265, kParamDynamicEqBandThreshold },
        { 0x10465, kParamDynamicEqBandThreshold },
        { 0x102A5, kParamDynamicEqBandThreshold },
        { 0x10266, kParamDynamicEqBandAttack },
        { 0x10466, kParamDynamicEqBandAttack },
        { 0x102A6, kParamDynamicEqBandAttack },
        { 0x10267, kParamDynamicEqBandRelease },
        { 0x10467, kParamDynamicEqBandRelease },
        { 0x102A7, kParamDynamicEqBandRelease },
        { 0x10268, kParamDynamicEqBandFilterType },
        { 0x10468, kParamDynamicEqBandFilterType },
        { 0x102A8, kParamDynamicEqBandFilterType },
    };
    return kMap;
}

// Translate Kotlin AIDL param ID to native C++ kParam ID.
// Returns -1 if the param is unknown (should be silently skipped).
static int translateParam(int ktParam) {
    auto& m = paramMap();
    auto it = m.find(ktParam);
    if (it == m.end()) {
        LOGW("Unknown Kotlin param 0x%x — skipping", ktParam);
        return -1;
    }
    return it->second;
}

// ── engine singleton ──────────────────────────────────────────────────────────

static std::unique_ptr<ViPER> gViper;
static std::mutex gMutex;
static uint32_t gSampleRate = 48000;

extern "C" {

JNIEXPORT void JNICALL
Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeCreate(
        JNIEnv *, jobject, jint sampleRate) {
    std::lock_guard<std::mutex> lock(gMutex);
    gSampleRate = static_cast<uint32_t>(sampleRate);
    gViper = std::make_unique<ViPER>();
    gViper->SetSamplingRate(gSampleRate);
    LOGI("ViPER engine created, sampleRate=%d", sampleRate);
}

JNIEXPORT void JNICALL
Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeRelease(
        JNIEnv *, jobject) {
    std::lock_guard<std::mutex> lock(gMutex);
    gViper.reset();
    LOGI("ViPER engine released");
}

// Process interleaved stereo float PCM in-place via a temporary buffer.
JNIEXPORT void JNICALL
Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeProcess(
        JNIEnv *env, jobject,
        jfloatArray input, jfloatArray output, jint frameCount) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gViper) return;

    const int total = frameCount * 2;
    jfloat *in  = env->GetFloatArrayElements(input,  nullptr);
    jfloat *out = env->GetFloatArrayElements(output, nullptr);

    std::vector<float> buf(in, in + total);
    gViper->Process(buf, static_cast<uint32_t>(frameCount));
    std::memcpy(out, buf.data(), total * sizeof(float));

    env->ReleaseFloatArrayElements(input,  in,  JNI_ABORT);
    env->ReleaseFloatArrayElements(output, out, 0);
}

// Dispatch an integer param, translating from Kotlin AIDL ID to native ID.
JNIEXPORT void JNICALL
Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeSetParam(
        JNIEnv *, jobject,
        jint param, jint val1, jint val2, jint val3) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gViper) return;
    int nativeParam = translateParam(param);
    if (nativeParam < 0) return;
    gViper->DispatchRawParam(nativeParam, val1, val2, val3, 0, nullptr);
}

// Dispatch a byte-array param (convolver kernel path, DDC coefficients).
JNIEXPORT void JNICALL
Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeSetParamBytes(
        JNIEnv *env, jobject,
        jint param, jbyteArray data) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gViper) return;
    int nativeParam = translateParam(param);
    if (nativeParam < 0) return;

    jsize totalBytes = env->GetArrayLength(data);
    jbyte *bytes = env->GetByteArrayElements(data, nullptr);

    if (nativeParam == kParamDdcCoefficients) {
        // AIDL wire format: [int32 LE: arrSize][arrSize floats 44100][arrSize floats 48000][padding]
        // arrSize = number of floats in each array (= numBiquads * 5).
        // C++ SetCoeffs(arr_size, float* coeffs_44100, float* coeffs_48000)
        if (totalBytes < 4) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return;
        }
        // Read arrSize from the 4-byte little-endian header
        uint32_t arrSize = static_cast<uint32_t>(
            (static_cast<uint8_t>(bytes[0])      ) |
            (static_cast<uint8_t>(bytes[1]) <<  8) |
            (static_cast<uint8_t>(bytes[2]) << 16) |
            (static_cast<uint8_t>(bytes[3]) << 24)
        );
        // Validate: need header + two float arrays
        jsize needed = 4 + static_cast<jsize>(arrSize) * 4 * 2;
        if (arrSize == 0 || needed > totalBytes) {
            LOGW("DDC: invalid payload arrSize=%u totalBytes=%d", arrSize, (int)totalBytes);
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return;
        }
        // coeffs_44100 starts at bytes[4], coeffs_48000 starts at bytes[4 + arrSize*4]
        const float *coeffs44100 = reinterpret_cast<const float *>(bytes + 4);
        const float *coeffs48000 = reinterpret_cast<const float *>(bytes + 4 + arrSize * 4);
        LOGI("DDC: SetCoeffs arrSize=%u", arrSize);
        gViper->DispatchRawParam(nativeParam, 0, 0, 0,
                                  arrSize,
                                  // DispatchRawParam takes signed char* — cast back
                                  reinterpret_cast<signed char *>(
                                      const_cast<float *>(coeffs44100)));
        // DispatchRawParam computes coeffs_48000 as arr + arr_size*sizeof(float), which
        // equals coeffs44100 + arrSize floats = coeffs48000 pointer — correct ✓

    } else if (nativeParam == kParamConvolverSetBuffer) {
        // AIDL wire format: [int32 LE: chunkIndex][int32 LE: floatsInChunk][float* data...][padding to 8192]
        // C++ SetKernelBuffer(float* buf, uint32_t size) copies 'size' floats from buf.
        if (totalBytes < 8) {
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return;
        }
        // Read floatsInChunk from bytes[4..7]
        uint32_t floatsInChunk = static_cast<uint32_t>(
            (static_cast<uint8_t>(bytes[4])      ) |
            (static_cast<uint8_t>(bytes[5]) <<  8) |
            (static_cast<uint8_t>(bytes[6]) << 16) |
            (static_cast<uint8_t>(bytes[7]) << 24)
        );
        jsize needed = 8 + static_cast<jsize>(floatsInChunk) * 4;
        if (floatsInChunk == 0 || needed > totalBytes) {
            LOGW("Convolver SetBuffer: invalid chunk floatsInChunk=%u totalBytes=%d",
                 floatsInChunk, (int)totalBytes);
            env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
            return;
        }
        // float data starts at bytes[8]
        const float *floatData = reinterpret_cast<const float *>(bytes + 8);
        LOGI("Convolver: SetBuffer floatsInChunk=%u", floatsInChunk);
        gViper->DispatchRawParam(nativeParam, 0, 0, 0,
                                  floatsInChunk,
                                  reinterpret_cast<signed char *>(
                                      const_cast<float *>(floatData)));

    } else {
        // All other byte-array params: pass raw bytes directly
        gViper->DispatchRawParam(nativeParam, 0, 0, 0,
                                  static_cast<uint32_t>(totalBytes),
                                  reinterpret_cast<signed char *>(bytes));
    }

    env->ReleaseByteArrayElements(data, bytes, JNI_ABORT);
}

JNIEXPORT void JNICALL
Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeSetSampleRate(
        JNIEnv *, jobject, jint sampleRate) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gViper) return;
    gSampleRate = static_cast<uint32_t>(sampleRate);
    gViper->SetSamplingRate(gSampleRate);
    gViper->RequestEffectsReset();
}

} // extern "C"

// ── LunarisDolby aliases ──────────────────────────────────────────────────────
// DolbyCaptureEngine (org.lunaris.dolby.audio) uses the same libvipercapture.so
// but the JVM looks up symbols by the calling class's package name.
// These thin wrappers forward to the existing V4A implementations so there is
// no code duplication and no separate native library needed.

extern "C" {

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_audio_DolbyCaptureEngine_nativeCreate(
        JNIEnv *env, jobject obj, jint sampleRate) {
    Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeCreate(env, obj, sampleRate);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_audio_DolbyCaptureEngine_nativeRelease(
        JNIEnv *env, jobject obj) {
    Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeRelease(env, obj);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_audio_DolbyCaptureEngine_nativeProcess(
        JNIEnv *env, jobject obj,
        jfloatArray input, jfloatArray output, jint frameCount) {
    Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeProcess(env, obj, input, output, frameCount);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_audio_DolbyCaptureEngine_nativeSetParam(
        JNIEnv *env, jobject obj,
        jint param, jint val1, jint val2, jint val3) {
    Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeSetParam(env, obj, param, val1, val2, val3);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_audio_DolbyCaptureEngine_nativeSetParamBytes(
        JNIEnv *env, jobject obj,
        jint param, jbyteArray data) {
    Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeSetParamBytes(env, obj, param, data);
}

JNIEXPORT void JNICALL
Java_org_lunaris_dolby_audio_DolbyCaptureEngine_nativeSetSampleRate(
        JNIEnv *env, jobject obj, jint sampleRate) {
    Java_com_llsl_viper4android_capture_ViperCaptureEngine_nativeSetSampleRate(env, obj, sampleRate);
}

} // extern "C" (LunarisDolby aliases)
