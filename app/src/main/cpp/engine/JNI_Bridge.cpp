#include <jni.h>

#include <memory>
#include <string>
#include <vector>

#include "AudioEngine.h"
#include "OboeOutput.h"

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
    const std::string result = chars ? chars : "";
    if (chars) {
        env->ReleaseStringUTFChars(value, chars);
    }
    return result;
}

bool StartPlaybackSources(dawengine::AudioEngine *engine,
                          int32_t sampleRate,
                          const std::vector<std::string> &paths,
                          const std::vector<float> &gains) {
    if (!engine) return false;

    engine->configureProject(sampleRate, 16);

    // Pause Oboe first so no [onAudioReady] call can be inside [AudioEngine::render]
    // while the JNI thread replaces or resets playback lanes/rings/sources.
    if (g_output) {
        if (!g_output->pauseForSafeEngineMutation()) {
            return false;
        }
    }

    if (!engine->setPlaybackSources(paths, gains)) {
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
        jstring outputPath) {
    auto *engine = EnsureEngine();
    if (!engine) return JNI_FALSE;

    engine->configureProject(sampleRate, fileBitDepth);
    const int32_t channelCount = channelMode == 1 ? 2 : 1;
    return engine->startRecording(channelCount, JStringToString(env, outputPath)) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStopRecording(JNIEnv *, jobject) {
    return g_engine && g_engine->stopRecording() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStartPlayback(
        JNIEnv *env,
        jobject,
        jint sampleRate,
        jstring wavPath,
        jfloat gain) {
    auto *engine = EnsureEngine();
    if (!engine) return JNI_FALSE;

    const std::string path = JStringToString(env, wavPath);
    const std::vector<std::string> paths{path};
    const std::vector<float> gains{gain};

    return StartPlaybackSources(engine, sampleRate, paths, gains) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStartMultiPlayback(
        JNIEnv *env,
        jobject,
        jint sampleRate,
        jobjectArray wavPaths,
        jfloatArray gainsArray) {
    auto *engine = EnsureEngine();
    if (!engine || !env || !wavPaths || !gainsArray) return JNI_FALSE;

    const jsize pathCount = env->GetArrayLength(wavPaths);
    const jsize gainCount = env->GetArrayLength(gainsArray);
    if (pathCount <= 0 || pathCount != gainCount) {
        StartPlaybackSources(engine, sampleRate, {}, {});
        return JNI_FALSE;
    }

    std::vector<std::string> paths;
    paths.reserve(static_cast<std::size_t>(pathCount));
    for (jsize i = 0; i < pathCount; ++i) {
        auto pathObject = static_cast<jstring>(env->GetObjectArrayElement(wavPaths, i));
        if (!pathObject) {
            StartPlaybackSources(engine, sampleRate, {}, {});
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

    return StartPlaybackSources(engine, sampleRate, paths, gains) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeSetPlaybackGain(
        JNIEnv *,
        jobject,
        jfloat gain) {
    if (g_engine) {
        g_engine->setPlaybackGain(gain);
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeIsPlaybackActive(JNIEnv *, jobject) {
    return g_engine && g_engine->isPlaybackActive() ? JNI_TRUE : JNI_FALSE;
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
