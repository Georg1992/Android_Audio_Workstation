#include <jni.h>
#include <android/log.h>
#include <SLES/OpenSLES.h>

#define LOG_TAG "AudioStreamingService"

SLObjectItf engineObject = nullptr;
SLEngineItf engineEngine = nullptr;

extern "C"
JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_audioprocessing_AudioStreamingService_createEngine(JNIEnv *env,
                                                                                    jobject thiz) {
    SLresult result;

    // Create the OpenSL ES engine
    result = slCreateEngine(&engineObject, 0, nullptr, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "slCreateEngine failed: %d", result);
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Engine created successfully");

    // Realize the engine
    result = (*engineObject)->Realize(engineObject, SL_BOOLEAN_TRUE);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Engine Realize failed: %d", result);
        return;
    }

    // Get the engine interface
    result = (*engineObject)->GetInterface(engineObject, SL_IID_ENGINE, &engineEngine);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetInterface failed: %d", result);
        return;
    }
    __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Engine interface obtained successfully");
}

extern "C"
JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_audioprocessing_AudioStreamingService_destroyEngine(JNIEnv *env,
                                                                                     jobject thiz) {
    if (engineEngine != nullptr) {
        __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Destroying engine interface");

        // Destroy the engine object
        if (engineObject != nullptr) {
            (*engineObject)->Destroy(engineObject);
            engineObject = nullptr;
            __android_log_print(ANDROID_LOG_INFO, LOG_TAG, "Engine object destroyed");
        }

        engineEngine = nullptr;
    } else {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "Engine was not created or already destroyed");
    }
}