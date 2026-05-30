#include <jni.h>

#include <android/log.h>

#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"
#include "OboeOutput.h"
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
                          const std::vector<int64_t> &laneLoopSourceEndMs) {
    if (!engine) return false;

    engine->configureProject(sampleRate, 16);

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
            laneLoopSourceEndMs)) {
        return false;
    }

    auto *output = EnsureOutput(engine);
    if (!output) {
        engine->stopPlayback();
        return false;
    }

    if (!output->ensureStarted(sampleRate, 2)) {
        engine->stopPlayback();
        return false;
    }
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
        jlongArray laneLoopSourceEndMs) {
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
                                loopSourceEnds)
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
        jlong loopSourceEndMs) {
    if (!g_engine) return -1;
    return g_engine->beginHotJoinLane(
        JStringToString(env, wavPath),
        gain,
        static_cast<int64_t>(clipStartMs),
        static_cast<int64_t>(clipDurationMs),
        loopEnabled == JNI_TRUE,
        static_cast<int64_t>(loopSourceStartMs),
        static_cast<int64_t>(loopSourceEndMs));
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
