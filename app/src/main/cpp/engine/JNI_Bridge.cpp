#include <jni.h>

#include <android/log.h>

#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"
#include "OboeOutput.h"
#include "AudioSyncLogConfig.h"
#include "TransportClockDiagnostics.h"
#include "OboeStreamCapabilityProbe.h"
#include "OverdubStartupOptimization.h"
#include "PlaybackLaneLifecycle.h"

namespace {

std::unique_ptr<dawengine::AudioEngine> g_engine;
std::unique_ptr<OboeOutput> g_output;

dawengine::AudioEngine *EnsureEngine() {
    if (!g_engine) {
        g_engine = std::make_unique<dawengine::AudioEngine>();
    }
    return g_engine.get();
}

OboeOutput *EnsureOutput(dawengine::AudioEngine *engine) {
    if (!g_output) {
        g_output = std::make_unique<OboeOutput>(engine);
    }
    return g_output.get();
}

std::string JStringToString(JNIEnv *env, jstring value) {
    if (!env || !value) return "";
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string result = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

std::vector<int64_t> JLongArrayToVector(JNIEnv *env, jlongArray array, jsize expectedSize) {
    std::vector<int64_t> values;
    if (!env || !array || expectedSize <= 0) {
        return values;
    }
    values.resize(static_cast<std::size_t>(expectedSize));
    env->GetLongArrayRegion(array, 0, expectedSize, reinterpret_cast<jlong *>(values.data()));
    if (env->ExceptionCheck()) {
        values.clear();
    }
    return values;
}

std::vector<uint8_t> JBooleanArrayToVector(JNIEnv *env, jbooleanArray array, jsize expectedSize) {
    std::vector<uint8_t> values;
    if (!env || !array || expectedSize <= 0) {
        return values;
    }
    values.resize(static_cast<std::size_t>(expectedSize));
    env->GetBooleanArrayRegion(array, 0, expectedSize, reinterpret_cast<jboolean *>(values.data()));
    if (env->ExceptionCheck()) {
        values.clear();
    }
    for (std::size_t i = 0; i < values.size(); ++i) {
        values[i] = values[i] != 0 ? static_cast<uint8_t>(1) : static_cast<uint8_t>(0);
    }
    return values;
}

std::vector<float> JFloatArrayToVector(JNIEnv *env, jfloatArray array, jsize expectedSize) {
    std::vector<float> values;
    if (!env || !array || expectedSize <= 0) {
        return values;
    }
    values.resize(static_cast<std::size_t>(expectedSize));
    env->GetFloatArrayRegion(array, 0, expectedSize, values.data());
    if (env->ExceptionCheck()) {
        values.clear();
    }
    return values;
}

bool StartPlaybackSources(dawengine::AudioEngine *engine,
                          int32_t sampleRate,
                          const std::vector<std::string> &paths,
                          const std::vector<float> &gains,
                          int64_t startPositionMs,
                          int64_t sessionTimelineEndMs,
                          const std::vector<int64_t> &laneClipStartMs,
                          const std::vector<int64_t> &laneClipDurationMs,
                          const std::vector<uint8_t> &laneLoopEnabled,
                          const std::vector<int64_t> &laneLoopSourceStartMs,
                          const std::vector<int64_t> &laneLoopSourceEndMs,
                          const std::vector<int64_t> &laneSourceTrimStartMs,
                          const std::vector<float> &lanePan) {
    if (!engine) return false;

    engine->configureProject(sampleRate, 16);
    engine->logPlaybackStartupMilestone("jni_start_multi_playback");

    // Pause Oboe first so no [onAudioReady] call can be inside [AudioEngine::render]
    // while the JNI thread replaces or resets playback lanes/rings/sources.
    if (g_output) {
        if (!g_output->pauseForSafeEngineMutation()) {
            return false;
        }
    }

    if (!engine->setPlaybackSources(
            paths,
            gains,
            startPositionMs,
            sessionTimelineEndMs,
            laneClipStartMs,
            laneClipDurationMs,
            laneLoopEnabled,
            laneLoopSourceStartMs,
            laneLoopSourceEndMs,
            laneSourceTrimStartMs,
            lanePan)) {
        return false;
    }
    engine->logPlaybackStartupMilestone("jni_after_set_playback_sources");

    auto *output = EnsureOutput(engine);
    if (!output) {
        engine->stopPlayback();
        return false;
    }
    engine->logPlaybackStartupMilestone("jni_before_oboe_ensure_started");

    if (!output->ensureStarted(sampleRate, 2)) {
        engine->stopPlayback();
        return false;
    }
    engine->logPlaybackStartupMilestone("jni_playback_ready");
    return true;
}

bool RearmOverdubPlaybackDuringRecording(dawengine::AudioEngine *engine,
                                         int32_t sampleRate,
                                         const std::vector<std::string> &paths,
                                         const std::vector<float> &gains,
                                         int64_t startPositionMs,
                                         int64_t sessionTimelineEndMs,
                                         const std::vector<int64_t> &laneClipStartMs,
                                         const std::vector<int64_t> &laneClipDurationMs,
                                         const std::vector<uint8_t> &laneLoopEnabled,
                                         const std::vector<int64_t> &laneLoopSourceStartMs,
                                         const std::vector<int64_t> &laneLoopSourceEndMs,
                                         const std::vector<int64_t> &laneSourceTrimStartMs,
                                         const std::vector<float> &lanePan) {
    if (!engine) {
        return false;
    }

    engine->configureProject(sampleRate, 16);
    engine->logPlaybackStartupMilestone("jni_rearm_overdub_playback");

    if (g_output) {
        if (!g_output->pauseForSafeEngineMutation()) {
            return false;
        }
    }

    if (!engine->rearmOverdubPlaybackDuringRecording(
            paths,
            gains,
            startPositionMs,
            sessionTimelineEndMs,
            laneClipStartMs,
            laneClipDurationMs,
            laneLoopEnabled,
            laneLoopSourceStartMs,
            laneLoopSourceEndMs,
            laneSourceTrimStartMs,
            lanePan)) {
        return false;
    }

    OboeOutput *output = EnsureOutput(engine);
    if (!output || !output->ensureStarted(sampleRate, 2)) {
        engine->stopPlayback();
        return false;
    }
    engine->logPlaybackStartupMilestone("jni_rearm_overdub_ready");
    return true;
}

bool StartOverdubRecordingSession(dawengine::AudioEngine *engine,
                                  int32_t sampleRate,
                                  const std::vector<std::string> &paths,
                                  const std::vector<float> &gains,
                                  int64_t startPositionMs,
                                  int64_t sessionTimelineEndMs,
                                  const std::vector<int64_t> &laneClipStartMs,
                                  const std::vector<int64_t> &laneClipDurationMs,
                                  const std::vector<uint8_t> &laneLoopEnabled,
                                  const std::vector<int64_t> &laneLoopSourceStartMs,
                                  const std::vector<int64_t> &laneLoopSourceEndMs,
                                  const std::vector<int64_t> &laneSourceTrimStartMs,
                                  const std::vector<float> &lanePan,
                                  int32_t channelCount,
                                  const std::string &recordingOutputPath) {
    if (!engine || recordingOutputPath.empty()) return false;

    engine->configureProject(sampleRate, 16);
    engine->logPlaybackStartupMilestone("jni_start_overdub_session");
    __android_log_print(
        ANDROID_LOG_INFO,
        "AudioSyncDiag",
        "[OVERDUB_DEFERRED_GATE] enabled=1");

    if (g_output) {
        if (!g_output->pauseForSafeEngineMutation()) {
            return false;
        }
    }

    OboeOutput *output = EnsureOutput(engine);
    if (!output) {
        return false;
    }

    if (!engine->startOverdubRecordingSession(
            paths,
            gains,
            startPositionMs,
            sessionTimelineEndMs,
            laneClipStartMs,
            laneClipDurationMs,
            laneLoopEnabled,
            laneLoopSourceStartMs,
            laneLoopSourceEndMs,
            laneSourceTrimStartMs,
            lanePan,
            channelCount,
            recordingOutputPath)) {
        return false;
    }
    engine->logPlaybackStartupMilestone("jni_after_start_overdub_session");
    engine->logPlaybackStartupMilestone("jni_before_oboe_ensure_started");

    if (!output->ensureStarted(sampleRate, 2)) {
        engine->stopRecording();
        return false;
    }

    engine->markOverdubJniReady();
    engine->logPlaybackStartupMilestone("jni_overdub_ready");
    overdub_startup_opt::logStartupTiming(engine, "jni_overdub_ready");
    return true;
}

} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStartRecording(
        JNIEnv *env,
        jobject,
        jint sampleRate,
        jint fileBitDepth,
        jint channelMode,
        jstring outputPath,
        jlong startPositionMs) {
    auto *engine = EnsureEngine();
    if (!engine) return JNI_FALSE;

    engine->configureProject(sampleRate, fileBitDepth);
    const int32_t channelCount = channelMode == 1 ? 2 : 1;
    return engine->startRecording(channelCount,
                                  JStringToString(env, outputPath),
                                  static_cast<int64_t>(startPositionMs))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStopRecording(JNIEnv *, jobject) {
    return g_engine && g_engine->stopRecording() ? JNI_TRUE : JNI_FALSE;
}

namespace {

constexpr jint kOboeStreamSnapshotFieldCount = 8;

jlongArray NewOboeStreamSnapshotArray(JNIEnv *env,
                                      const dawengine::AudioEngine::OboeStreamSnapshot &snapshot) {
    if (!env) {
        return nullptr;
    }
    jlongArray array = env->NewLongArray(kOboeStreamSnapshotFieldCount);
    if (!array) {
        return nullptr;
    }
    const jlong values[kOboeStreamSnapshotFieldCount] = {
        static_cast<jlong>(snapshot.sampleRateHz),
        static_cast<jlong>(snapshot.channelCount),
        static_cast<jlong>(snapshot.framesPerBurst),
        static_cast<jlong>(snapshot.bufferCapacityInFrames),
        static_cast<jlong>(snapshot.bufferSizeInFrames),
        static_cast<jlong>(snapshot.performanceMode),
        static_cast<jlong>(snapshot.sharingMode),
        static_cast<jlong>(snapshot.audioSessionId),
    };
    env->SetLongArrayRegion(array, 0, kOboeStreamSnapshotFieldCount, values);
    return array;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetInputStreamSnapshot(
        JNIEnv *env,
        jobject) {
    if (!g_engine) {
        return nullptr;
    }
    return NewOboeStreamSnapshotArray(env, g_engine->inputStreamSnapshot());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetOutputStreamSnapshot(
        JNIEnv *env,
        jobject) {
    if (!g_output) {
        return nullptr;
    }
    return NewOboeStreamSnapshotArray(env, g_output->outputStreamSnapshot());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetPlaybackSessionTimings(
        JNIEnv *env,
        jobject) {
    if (!env || !g_engine) {
        return nullptr;
    }
    const dawengine::AudioEngine::PlaybackSessionTimings timings =
        g_engine->playbackSessionTimings();
    constexpr jsize kPlaybackSessionTimingsFieldCount = 18;
    jlongArray array = env->NewLongArray(kPlaybackSessionTimingsFieldCount);
    if (!array) {
        return nullptr;
    }
    const jlong values[kPlaybackSessionTimingsFieldCount] = {
        timings.playbackArmSteadyNs,
        timings.firstInputSampleSteadyNs,
        timings.firstNonSilentOutputSteadyNs,
        timings.firstAudibleOutputSteadyNs,
        static_cast<jlong>(timings.deferEnabled),
        static_cast<jlong>(timings.prerollFrames),
        static_cast<jlong>(timings.ioBatchFrames),
        static_cast<jlong>(timings.recordReadFrames),
        timings.playbackArmTransportStartFrame,
        timings.firstNonSilentTransportFrame,
        timings.firstAudiblePeakTransportFrame,
        timings.firstAudiblePeakMicro,
        timings.openInputBeginSteadyNs,
        timings.openInputDoneSteadyNs,
        timings.oboeStreamOpenBeginSteadyNs,
        timings.oboeStreamOpenDoneSteadyNs,
        timings.oboeStreamStartDoneSteadyNs,
        timings.firstOboeCallbackSteadyNs,
    };
    env->SetLongArrayRegion(array, 0, kPlaybackSessionTimingsFieldCount, values);
    return array;
}

namespace {

constexpr jint kStreamCapabilityProbeFieldCount = 15;

jlongArray NewStreamCapabilityProbeArray(JNIEnv *env,
                                         const oboe_capability::StreamCapabilityProbe &probe) {
    if (!env) {
        return nullptr;
    }
    jlongArray array = env->NewLongArray(kStreamCapabilityProbeFieldCount);
    if (!array) {
        return nullptr;
    }
    const jlong estimatedLatencyMicro =
        probe.estimatedStreamLatencyMs >= 0.0
            ? static_cast<jlong>(probe.estimatedStreamLatencyMs * 1000.0)
            : -1L;
    const jlong values[kStreamCapabilityProbeFieldCount] = {
        static_cast<jlong>(probe.sampleRateHz),
        static_cast<jlong>(probe.channelCount),
        static_cast<jlong>(probe.framesPerBurst),
        static_cast<jlong>(probe.bufferCapacityInFrames),
        static_cast<jlong>(probe.bufferSizeInFrames),
        static_cast<jlong>(probe.performanceModeActual),
        static_cast<jlong>(probe.sharingModeActual),
        static_cast<jlong>(probe.audioSessionId),
        static_cast<jlong>(probe.audioApi),
        static_cast<jlong>(probe.format),
        estimatedLatencyMicro,
        static_cast<jlong>(probe.timestampAvailable),
        static_cast<jlong>(probe.timestampStable),
        static_cast<jlong>(probe.blockFrames),
        static_cast<jlong>(probe.xRunCount),
    };
    env->SetLongArrayRegion(array, 0, kStreamCapabilityProbeFieldCount, values);
    return array;
}

constexpr jint kSoftwareBufferProfileFieldCount = 6;
constexpr jint kOutputCallbackCostFieldCount = 11;
constexpr jint kInputLoopCostFieldCount = 10;

jlongArray NewOutputCallbackCostArray(JNIEnv *env,
                                      const dawengine::AudioEngine::CallbackCostSnapshot &snapshot) {
    if (!env) {
        return nullptr;
    }
    jlongArray array = env->NewLongArray(kOutputCallbackCostFieldCount);
    if (!array) {
        return nullptr;
    }
    const jlong values[kOutputCallbackCostFieldCount] = {
        static_cast<jlong>(snapshot.callbackFrames),
        snapshot.sampleCount,
        snapshot.callbackMinUs,
        snapshot.callbackAvgUs,
        snapshot.callbackMaxUs,
        snapshot.callbackP95Us,
        snapshot.renderMinUs,
        snapshot.renderAvgUs,
        snapshot.renderMaxUs,
        snapshot.renderP95Us,
        static_cast<jlong>(snapshot.xRunCount),
    };
    env->SetLongArrayRegion(array, 0, kOutputCallbackCostFieldCount, values);
    return array;
}

jlongArray NewInputLoopCostArray(JNIEnv *env,
                                 const dawengine::AudioEngine::InputLoopCostSnapshot &snapshot) {
    if (!env) {
        return nullptr;
    }
    jlongArray array = env->NewLongArray(kInputLoopCostFieldCount);
    if (!array) {
        return nullptr;
    }
    const jlong values[kInputLoopCostFieldCount] = {
        static_cast<jlong>(snapshot.readFrames),
        snapshot.sampleCount,
        snapshot.readBlockingMinUs,
        snapshot.readBlockingAvgUs,
        snapshot.readBlockingMaxUs,
        snapshot.readBlockingP95Us,
        snapshot.processingMinUs,
        snapshot.processingAvgUs,
        snapshot.processingMaxUs,
        snapshot.processingP95Us,
    };
    env->SetLongArrayRegion(array, 0, kInputLoopCostFieldCount, values);
    return array;
}

jlongArray NewSoftwareBufferProfileArray(JNIEnv *env,
                                         const dawengine::AudioEngine::SoftwareBufferProfile &profile) {
    if (!env) {
        return nullptr;
    }
    jlongArray array = env->NewLongArray(kSoftwareBufferProfileFieldCount);
    if (!array) {
        return nullptr;
    }
    const jlong values[kSoftwareBufferProfileFieldCount] = {
        static_cast<jlong>(profile.ringDurationSeconds),
        static_cast<jlong>(profile.prerollWallMs),
        static_cast<jlong>(profile.ioBatchFrames),
        static_cast<jlong>(profile.inputReadFrames),
        static_cast<jlong>(profile.ioIdleSleepMs),
        static_cast<jlong>(profile.inputReadTimeoutMs),
    };
    env->SetLongArrayRegion(array, 0, kSoftwareBufferProfileFieldCount, values);
    return array;
}

} // namespace

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeProbeOutputStreamCapability(
        JNIEnv *env,
        jobject) {
    if (!g_output) {
        return nullptr;
    }
    const auto stream = g_output->streamForDiagnostics();
    if (!stream) {
        return nullptr;
    }
    return NewStreamCapabilityProbeArray(
        env,
        oboe_capability::probeStream(stream.get(), g_output->lastCallbackFrames()));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeProbeInputStreamCapability(
        JNIEnv *env,
        jobject) {
    if (!g_engine) {
        return nullptr;
    }
    const auto stream = g_engine->inputStreamForDiagnostics();
    if (!stream) {
        return nullptr;
    }
    return NewStreamCapabilityProbeArray(
        env,
        oboe_capability::probeStream(stream.get(), g_engine->inputReadBlockFrames()));
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetSoftwareBufferProfile(
        JNIEnv *env,
        jobject) {
    return NewSoftwareBufferProfileArray(
        env,
        g_engine ? g_engine->softwareBufferProfile()
                 : dawengine::AudioEngine::SoftwareBufferProfile{});
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetOutputCallbackCostSnapshot(
        JNIEnv *env,
        jobject) {
    if (!g_engine) {
        return nullptr;
    }
    return NewOutputCallbackCostArray(env, g_engine->outputCallbackCostSnapshot());
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetInputLoopCostSnapshot(
        JNIEnv *env,
        jobject) {
    if (!g_engine) {
        return nullptr;
    }
    return NewInputLoopCostArray(env, g_engine->inputLoopCostSnapshot());
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeSyncAudioSyncLogConfig(
        JNIEnv *,
        jobject,
        jboolean clockValidationMode,
        jboolean detailedStartupLogsEnabled,
        jboolean rawTimestampSpamEnabled,
        jboolean transportFrameVerboseEnabled) {
    audio_sync_log_config::syncFromJvm(
        clockValidationMode == JNI_TRUE,
        detailedStartupLogsEnabled == JNI_TRUE,
        rawTimestampSpamEnabled == JNI_TRUE,
        transportFrameVerboseEnabled == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeSetSessionTransportLatenciesNs(
        JNIEnv *,
        jobject,
        jlong inputLatencyNs,
        jlong outputLatencyNs) {
    if (g_engine) {
        g_engine->setSessionTransportLatenciesNs(static_cast<int64_t>(inputLatencyNs),
                                                 static_cast<int64_t>(outputLatencyNs));
    }
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetLastPlacementClockDeltaMs(
        JNIEnv *,
        jobject) {
    return static_cast<jlong>(transport_clock_diag::lastPlacementClockDeltaMs());
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetRecordingFirstSampleTransportPositionMs(
        JNIEnv *,
        jobject) {
    return g_engine ? static_cast<jlong>(g_engine->recordingFirstSampleTransportPositionMs())
                    : static_cast<jlong>(dawengine::AudioEngine::kRecordingFirstSampleTransportUnset);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetRecordingFirstSampleTransportFrame(
        JNIEnv *,
        jobject) {
    return g_engine ? static_cast<jlong>(g_engine->recordingFirstSampleTransportFrame())
                    : static_cast<jlong>(dawengine::AudioEngine::kRecordingFirstSampleTransportUnset);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetRecordingCapturedFrameCount(
        JNIEnv *,
        jobject) {
    return g_engine ? static_cast<jlong>(g_engine->recordingCapturedFrameCount()) : 0L;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetRecordingCapturedDurationMs(
        JNIEnv *,
        jobject) {
    return g_engine ? static_cast<jlong>(g_engine->recordingCapturedDurationMs()) : 0L;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStartOverdubRecordingSession(
        JNIEnv *env,
        jobject,
        jint sampleRate,
        jobjectArray wavPaths,
        jfloatArray gainsArray,
        jlong startPositionMs,
        jlong sessionTimelineEndMs,
        jlongArray laneClipStartMs,
        jlongArray laneClipDurationMs,
        jbooleanArray laneLoopEnabled,
        jlongArray laneLoopSourceStartMs,
        jlongArray laneLoopSourceEndMs,
        jlongArray laneSourceTrimStartMs,
        jfloatArray lanePan,
        jint channelMode,
        jstring routeKeyJ,
        jstring outputPathJ) {
    auto *engine = EnsureEngine();
    if (!engine || !env || !wavPaths || !gainsArray || !outputPathJ) return JNI_FALSE;

    const jsize pathCount = env->GetArrayLength(wavPaths);
    const jsize gainCount = env->GetArrayLength(gainsArray);
    if (pathCount <= 0 || pathCount != gainCount) {
        return JNI_FALSE;
    }

    std::vector<std::string> paths;
    paths.reserve(static_cast<std::size_t>(pathCount));
    for (jsize i = 0; i < pathCount; ++i) {
        auto pathObject = static_cast<jstring>(env->GetObjectArrayElement(wavPaths, i));
        if (!pathObject) {
            return JNI_FALSE;
        }
        paths.push_back(JStringToString(env, pathObject));
        env->DeleteLocalRef(pathObject);
    }

    std::vector<float> gains(static_cast<std::size_t>(gainCount));
    env->GetFloatArrayRegion(gainsArray, 0, gainCount, gains.data());
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    std::vector<int64_t> clipStarts = JLongArrayToVector(env, laneClipStartMs, pathCount);
    std::vector<int64_t> clipDurations = JLongArrayToVector(env, laneClipDurationMs, pathCount);
    std::vector<uint8_t> loopEnabled = JBooleanArrayToVector(env, laneLoopEnabled, pathCount);
    std::vector<int64_t> loopSourceStarts =
        JLongArrayToVector(env, laneLoopSourceStartMs, pathCount);
    std::vector<int64_t> loopSourceEnds =
        JLongArrayToVector(env, laneLoopSourceEndMs, pathCount);
    std::vector<int64_t> sourceTrimStarts =
        JLongArrayToVector(env, laneSourceTrimStartMs, pathCount);
    std::vector<float> pans = JFloatArrayToVector(env, lanePan, pathCount);
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    const int32_t channelCount = channelMode == 1 ? 2 : 1;
    engine->setSessionInputRouteKey(routeKeyJ ? JStringToString(env, routeKeyJ) : std::string{});
    return StartOverdubRecordingSession(
               engine,
               sampleRate,
               paths,
               gains,
               static_cast<int64_t>(startPositionMs),
               static_cast<int64_t>(sessionTimelineEndMs),
               clipStarts,
               clipDurations,
               loopEnabled,
               loopSourceStarts,
               loopSourceEnds,
               sourceTrimStarts,
               pans,
               channelCount,
               JStringToString(env, outputPathJ))
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetRecordingInputLevel(JNIEnv *, jobject) {
    return g_engine ? g_engine->recordingInputLevel() : 0.0f;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetMasterPeakHoldLinear(JNIEnv *, jobject) {
    return g_engine ? g_engine->masterPeakHoldLinear() : 0.0f;
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeResetMasterPeakHold(JNIEnv *, jobject) {
    if (g_engine) {
        g_engine->resetMasterPeakHold();
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStartMultiPlayback(
        JNIEnv *env,
        jobject,
        jint sampleRate,
        jobjectArray wavPaths,
        jfloatArray gainsArray,
        jlong startPositionMs,
        jlong sessionTimelineEndMs,
        jlongArray laneClipStartMs,
        jlongArray laneClipDurationMs,
        jbooleanArray laneLoopEnabled,
        jlongArray laneLoopSourceStartMs,
        jlongArray laneLoopSourceEndMs,
        jlongArray laneSourceTrimStartMs,
        jfloatArray lanePan) {
    auto *engine = EnsureEngine();
    if (!engine || !env || !wavPaths || !gainsArray) return JNI_FALSE;

    const jsize pathCount = env->GetArrayLength(wavPaths);
    const jsize gainCount = env->GetArrayLength(gainsArray);
    if (pathCount <= 0 || pathCount != gainCount) {
        StartPlaybackSources(
            engine,
            sampleRate,
            {},
            {},
            startPositionMs,
            sessionTimelineEndMs,
            {},
            {},
            {},
            {},
            {},
            {},
            {});
        return JNI_FALSE;
    }

    std::vector<std::string> paths;
    paths.reserve(static_cast<std::size_t>(pathCount));
    for (jsize i = 0; i < pathCount; ++i) {
        auto pathObject = static_cast<jstring>(env->GetObjectArrayElement(wavPaths, i));
        if (!pathObject) {
            StartPlaybackSources(
                engine,
                sampleRate,
                {},
                {},
                startPositionMs,
                sessionTimelineEndMs,
                {},
                {},
                {},
                {},
                {},
                {},
                {});
            return JNI_FALSE;
        }
        paths.push_back(JStringToString(env, pathObject));
        env->DeleteLocalRef(pathObject);
    }

    std::vector<float> gains(static_cast<std::size_t>(gainCount));
    env->GetFloatArrayRegion(gainsArray, 0, gainCount, gains.data());
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    std::vector<int64_t> clipStarts = JLongArrayToVector(env, laneClipStartMs, pathCount);
    std::vector<int64_t> clipDurations = JLongArrayToVector(env, laneClipDurationMs, pathCount);
    std::vector<uint8_t> loopEnabled = JBooleanArrayToVector(env, laneLoopEnabled, pathCount);
    std::vector<int64_t> loopSourceStarts =
        JLongArrayToVector(env, laneLoopSourceStartMs, pathCount);
    std::vector<int64_t> loopSourceEnds =
        JLongArrayToVector(env, laneLoopSourceEndMs, pathCount);
    std::vector<int64_t> sourceTrimStarts =
        JLongArrayToVector(env, laneSourceTrimStartMs, pathCount);
    std::vector<float> pans = JFloatArrayToVector(env, lanePan, pathCount);
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    return StartPlaybackSources(engine,
                                sampleRate,
                                paths,
                                gains,
                                startPositionMs,
                                sessionTimelineEndMs,
                                clipStarts,
                                clipDurations,
                                loopEnabled,
                                loopSourceStarts,
                                loopSourceEnds,
                                sourceTrimStarts,
                                pans)
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeRearmOverdubPlaybackDuringRecording(
        JNIEnv *env,
        jobject,
        jint sampleRate,
        jobjectArray wavPaths,
        jfloatArray gainsArray,
        jlong startPositionMs,
        jlong sessionTimelineEndMs,
        jlongArray laneClipStartMs,
        jlongArray laneClipDurationMs,
        jbooleanArray laneLoopEnabled,
        jlongArray laneLoopSourceStartMs,
        jlongArray laneLoopSourceEndMs,
        jlongArray laneSourceTrimStartMs,
        jfloatArray lanePan) {
    auto *engine = EnsureEngine();
    if (!engine || !env || !wavPaths || !gainsArray) return JNI_FALSE;

    const jsize pathCount = env->GetArrayLength(wavPaths);
    const jsize gainCount = env->GetArrayLength(gainsArray);
    if (pathCount <= 0 || pathCount != gainCount) {
        return JNI_FALSE;
    }

    std::vector<std::string> paths;
    paths.reserve(static_cast<std::size_t>(pathCount));
    for (jsize i = 0; i < pathCount; ++i) {
        auto pathObject = static_cast<jstring>(env->GetObjectArrayElement(wavPaths, i));
        if (!pathObject) {
            return JNI_FALSE;
        }
        paths.push_back(JStringToString(env, pathObject));
        env->DeleteLocalRef(pathObject);
    }

    std::vector<float> gains(static_cast<std::size_t>(gainCount));
    env->GetFloatArrayRegion(gainsArray, 0, gainCount, gains.data());
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    std::vector<int64_t> clipStarts = JLongArrayToVector(env, laneClipStartMs, pathCount);
    std::vector<int64_t> clipDurations = JLongArrayToVector(env, laneClipDurationMs, pathCount);
    std::vector<uint8_t> loopEnabled = JBooleanArrayToVector(env, laneLoopEnabled, pathCount);
    std::vector<int64_t> loopSourceStarts =
        JLongArrayToVector(env, laneLoopSourceStartMs, pathCount);
    std::vector<int64_t> loopSourceEnds =
        JLongArrayToVector(env, laneLoopSourceEndMs, pathCount);
    std::vector<int64_t> sourceTrimStarts =
        JLongArrayToVector(env, laneSourceTrimStartMs, pathCount);
    std::vector<float> pans = JFloatArrayToVector(env, lanePan, pathCount);
    if (env->ExceptionCheck()) {
        return JNI_FALSE;
    }

    return RearmOverdubPlaybackDuringRecording(engine,
                                               sampleRate,
                                               paths,
                                               gains,
                                               startPositionMs,
                                               sessionTimelineEndMs,
                                               clipStarts,
                                               clipDurations,
                                               loopEnabled,
                                               loopSourceStarts,
                                               loopSourceEnds,
                                               sourceTrimStarts,
                                               pans)
               ? JNI_TRUE
               : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeSetPlaybackLaneGain(
        JNIEnv *,
        jobject,
        jint laneIndex,
        jfloat gain) {
    if (g_engine && laneIndex >= 0) {
        g_engine->setPlaybackLaneGain(static_cast<std::size_t>(laneIndex), gain);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeSetPlaybackLanePan(
        JNIEnv *,
        jobject,
        jint laneIndex,
        jfloat pan) {
    if (g_engine && laneIndex >= 0) {
        g_engine->setPlaybackLanePan(static_cast<std::size_t>(laneIndex), pan);
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeSetPlaybackLaneAudible(
        JNIEnv *,
        jobject,
        jint laneIndex,
        jboolean audible) {
    if (g_engine && laneIndex >= 0) {
        g_engine->setPlaybackLaneAudible(static_cast<std::size_t>(laneIndex), audible == JNI_TRUE);
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeBeginHotJoinLane(
        JNIEnv *env,
        jobject,
        jstring wavPath,
        jfloat gain,
        jlong clipStartMs,
        jlong clipDurationMs,
        jboolean loopEnabled,
        jlong loopSourceStartMs,
        jlong loopSourceEndMs,
        jlong sourceTrimStartMs,
        jfloat pan) {
    if (!g_engine) return -1;
    return g_engine->beginHotJoinLane(
        JStringToString(env, wavPath),
        gain,
        static_cast<int64_t>(clipStartMs),
        static_cast<int64_t>(clipDurationMs),
        loopEnabled == JNI_TRUE,
        static_cast<int64_t>(loopSourceStartMs),
        static_cast<int64_t>(loopSourceEndMs),
        static_cast<int64_t>(sourceTrimStartMs),
        pan);
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeCancelHotJoinLane(
        JNIEnv *,
        jobject,
        jint laneIndex) {
    if (g_engine && laneIndex >= 0) {
        g_engine->cancelHotJoinLane(static_cast<std::size_t>(laneIndex));
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetPlaybackLaneLifecycle(
        JNIEnv *,
        jobject,
        jint laneIndex) {
    if (!g_engine || laneIndex < 0) {
        return static_cast<jint>(dawengine::PlaybackLaneLifecycle::Inactive);
    }
    return static_cast<jint>(g_engine->laneLifecycle(static_cast<std::size_t>(laneIndex)));
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeIsPlaybackActive(JNIEnv *, jobject) {
    return g_engine && g_engine->isPlaybackActive() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetTransportFrame(JNIEnv *, jobject) {
    return g_engine ? static_cast<jlong>(g_engine->transportFrame()) : 0L;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetTransportStartFrame(JNIEnv *, jobject) {
    return g_engine ? static_cast<jlong>(g_engine->transportStartFrame()) : 0L;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeGetTransportPositionMs(JNIEnv *, jobject) {
    return g_engine ? static_cast<jlong>(g_engine->transportPositionMs()) : 0L;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStopPlayback(JNIEnv *, jobject) {
    if (g_output) {
        if (!g_output->pauseForSafeEngineMutation()) {
            return JNI_FALSE;
        }
    }
    if (g_engine) {
        // Keep the source open; [ensureStarted] resumes the paused stream on
        // the next play so we don't pay reopen cost while the project screen
        // is alive.
        g_engine->stopPlayback();
    }
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeReleaseEngine(JNIEnv *, jobject) {
    if (g_output) {
        g_output->release();
    }
    if (g_engine) {
        // Ensure input capture is torn down (joins record thread, closes mic stream)
        // before dropping playback I/O — releasePlaybackResources must not run mid-record.
        g_engine->stopRecording();
        g_engine->releasePlaybackResources();
    }
}

extern "C" JNIEXPORT jint JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeRenderOfflineMixdown(
        JNIEnv *env,
        jobject thiz,
        jint sampleRate,
        jobjectArray wavPaths,
        jfloatArray gainsArray,
        jlong startPositionMs,
        jlong sessionTimelineEndMs,
        jlongArray laneClipStartMs,
        jlongArray laneClipDurationMs,
        jbooleanArray laneLoopEnabled,
        jlongArray laneLoopSourceStartMs,
        jlongArray laneLoopSourceEndMs,
        jlongArray laneSourceTrimStartMs,
        jfloatArray lanePan,
        jstring outputPathJ) {
    auto *engine = EnsureEngine();
    if (!engine || !env || !wavPaths || !gainsArray) {
        return static_cast<jint>(dawengine::AudioEngine::OfflineMixdownStatus::Failed);
    }

    if (g_output && !g_output->pauseForSafeEngineMutation()) {
        return static_cast<jint>(dawengine::AudioEngine::OfflineMixdownStatus::Failed);
    }
    if (engine->isPlaybackActive()) {
        engine->stopPlayback();
    }

    const jsize pathCount = env->GetArrayLength(wavPaths);
    const jsize gainCount = env->GetArrayLength(gainsArray);
    if (pathCount <= 0 || pathCount != gainCount) {
        return static_cast<jint>(dawengine::AudioEngine::OfflineMixdownStatus::Failed);
    }

    std::vector<std::string> paths;
    paths.reserve(static_cast<std::size_t>(pathCount));
    for (jsize i = 0; i < pathCount; ++i) {
        auto pathObject = static_cast<jstring>(env->GetObjectArrayElement(wavPaths, i));
        if (!pathObject) {
            return static_cast<jint>(dawengine::AudioEngine::OfflineMixdownStatus::Failed);
        }
        paths.push_back(JStringToString(env, pathObject));
        env->DeleteLocalRef(pathObject);
    }

    std::vector<float> gains(static_cast<std::size_t>(gainCount));
    env->GetFloatArrayRegion(gainsArray, 0, gainCount, gains.data());
    if (env->ExceptionCheck()) {
        return static_cast<jint>(dawengine::AudioEngine::OfflineMixdownStatus::Failed);
    }

    std::vector<int64_t> clipStarts = JLongArrayToVector(env, laneClipStartMs, pathCount);
    std::vector<int64_t> clipDurations = JLongArrayToVector(env, laneClipDurationMs, pathCount);
    std::vector<uint8_t> loopEnabled = JBooleanArrayToVector(env, laneLoopEnabled, pathCount);
    std::vector<int64_t> loopSourceStarts =
        JLongArrayToVector(env, laneLoopSourceStartMs, pathCount);
    std::vector<int64_t> loopSourceEnds =
        JLongArrayToVector(env, laneLoopSourceEndMs, pathCount);
    std::vector<int64_t> sourceTrimStarts =
        JLongArrayToVector(env, laneSourceTrimStartMs, pathCount);
    std::vector<float> pans = JFloatArrayToVector(env, lanePan, pathCount);
    if (env->ExceptionCheck()) {
        return static_cast<jint>(dawengine::AudioEngine::OfflineMixdownStatus::Failed);
    }

    const std::string outputPath = JStringToString(env, outputPathJ);
    jclass clazz = env->GetObjectClass(thiz);
    jmethodID progressMethod =
        env->GetMethodID(clazz, "dispatchOfflineMixdownProgress", "(F)V");

    const dawengine::AudioEngine::OfflineMixdownStatus status = engine->renderOfflineMixdown(
        sampleRate,
        paths,
        gains,
        static_cast<int64_t>(startPositionMs),
        static_cast<int64_t>(sessionTimelineEndMs),
        clipStarts,
        clipDurations,
        loopEnabled,
        loopSourceStarts,
        loopSourceEnds,
        sourceTrimStarts,
        pans,
        outputPath,
        [&](const float progress) {
            if (progressMethod != nullptr) {
                env->CallVoidMethod(thiz, progressMethod, progress);
            }
        });

    return static_cast<jint>(status);
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeCancelOfflineMixdown(JNIEnv *, jobject) {
    if (g_engine) {
        g_engine->requestOfflineMixdownCancel();
    }
}
