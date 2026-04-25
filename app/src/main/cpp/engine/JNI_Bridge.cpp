#include <jni.h>

#include <memory>
#include <string>

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

    engine->configureProject(sampleRate, 16);
    const std::string path = JStringToString(env, wavPath);

    // Arm the engine first so the source is ready before we start the audio
    // device. The engine handles "same path" cheaply (rewind only) so repeat
    // plays don't reopen the WAV.
    if (!engine->setPlaybackSource(path, gain)) {
        return JNI_FALSE;
    }

    auto *output = EnsureOutput(engine);
    if (!output) {
        engine->stopPlayback();
        return JNI_FALSE;
    }

    if (!output->ensureStarted(sampleRate, 2)) {
        engine->stopPlayback();
        return JNI_FALSE;
    }
    return JNI_TRUE;
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
    if (g_engine) {
        // Pause but keep the source open and the output stream running so the
        // next press is instant. Holding the audio device open is cheap as
        // long as the screen is alive; [nativeReleaseEngine] tears it down
        // when the project screen is disposed.
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
        g_engine->releasePlaybackResources();
    }
}
