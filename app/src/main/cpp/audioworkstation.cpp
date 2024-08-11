#include <jni.h>
#include <android/log.h>
#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>

#define LOG_TAG "AudioStreamingService"

SLObjectItf engineObject = nullptr;
SLEngineItf engineEngine = nullptr;
SLObjectItf outputMixObject = nullptr;
SLObjectItf recorderObject = nullptr;
SLRecordItf recorderRecord = nullptr;
SLObjectItf playerObject = nullptr;
SLPlayItf playerPlay = nullptr;
SLAndroidSimpleBufferQueueItf recorderBufferQueue = nullptr;
SLAndroidSimpleBufferQueueItf playerBufferQueue = nullptr;

const SLInterfaceID playerIID[2] = { SL_IID_PLAY, SL_IID_BUFFERQUEUE };
const SLboolean playerReq[2] = { SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE };

const SLInterfaceID recorderIID[2] = { SL_IID_RECORD, SL_IID_ANDROIDSIMPLEBUFFERQUEUE };
const SLboolean recorderReq[2] = { SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE };

short buffer[1024];

void recorderCallback(SLAndroidSimpleBufferQueueItf bq, void *context) {
    // Enqueue the buffer data into the player buffer queue for playback
    (*playerBufferQueue)->Enqueue(playerBufferQueue, buffer, sizeof(buffer));

    // Re-enqueue the same buffer to the recorder to continue streaming
    (*recorderBufferQueue)->Enqueue(recorderBufferQueue, buffer, sizeof(buffer));
}

void createEngine(){
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

void createOutputMix(){
    SLresult result;
    result = (*engineEngine)->CreateOutputMix(engineEngine, &outputMixObject, 0, nullptr, nullptr);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "CreateOutputMix failed: %d", result);
        return;
    }
    result = (*outputMixObject)->Realize(outputMixObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "OutputMix Realize failed: %d", result);
    }
}

void startAudioStreaming() {
    SLresult result;

    // Set up the recorder
    SLDataLocator_IODevice loc_dev = {SL_DATALOCATOR_IODEVICE, SL_IODEVICE_AUDIOINPUT, SL_DEFAULTDEVICEID_AUDIOINPUT, nullptr};
    SLDataSource audioSrc = {&loc_dev, nullptr};

    SLDataLocator_AndroidSimpleBufferQueue loc_bq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 1};
    SLDataFormat_PCM format_pcm = {
            SL_DATAFORMAT_PCM, 1, SL_SAMPLINGRATE_44_1,
            SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
            SL_SPEAKER_FRONT_CENTER, SL_BYTEORDER_LITTLEENDIAN
    };
    SLDataSink audioSnk = {&loc_bq, &format_pcm};

    result = (*engineEngine)->CreateAudioRecorder(engineEngine, &recorderObject, &audioSrc, &audioSnk, 2, recorderIID, recorderReq);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "CreateAudioRecorder failed: %d", result);
        return;
    }
    result = (*recorderObject)->Realize(recorderObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Recorder Realize failed: %d", result);
        return;
    }
    result = (*recorderObject)->GetInterface(recorderObject, SL_IID_RECORD, &recorderRecord);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetRecorderInterface failed: %d", result);
        return;
    }
    result = (*recorderObject)->GetInterface(recorderObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &recorderBufferQueue);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetRecorderBufferQueueInterface failed: %d", result);
        return;
    }

    // Set up the player
    SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, outputMixObject};
    SLDataSink audioSnkPlayer = {&loc_outmix, nullptr};

    result = (*engineEngine)->CreateAudioPlayer(engineEngine, &playerObject, &audioSrc, &audioSnkPlayer, 2, playerIID, playerReq);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "CreateAudioPlayer failed: %d", result);
        return;
    }
    result = (*playerObject)->Realize(playerObject, SL_BOOLEAN_FALSE);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Player Realize failed: %d", result);
        return;
    }
    result = (*playerObject)->GetInterface(playerObject, SL_IID_PLAY, &playerPlay);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetPlayerPlayInterface failed: %d", result);
        return;
    }
    result = (*playerObject)->GetInterface(playerObject, SL_IID_BUFFERQUEUE, &playerBufferQueue);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "GetPlayerBufferQueueInterface failed: %d", result);
        return;
    }

    // Register the callback function with the recorder buffer queue
    (*recorderBufferQueue)->RegisterCallback(recorderBufferQueue, recorderCallback, nullptr);

    // Start the recording
    result = (*recorderRecord)->SetRecordState(recorderRecord, SL_RECORDSTATE_RECORDING);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SetRecordState failed: %d", result);
        return;
    }

    // Start the player
    result = (*playerPlay)->SetPlayState(playerPlay, SL_PLAYSTATE_PLAYING);
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "SetPlayState failed: %d", result);
        return;
    }

    // Enqueue the initial buffer
    result = (*recorderBufferQueue)->Enqueue(recorderBufferQueue, buffer, sizeof(buffer));
    if (result != SL_RESULT_SUCCESS) {
        __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, "Enqueue failed: %d", result);
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_audioprocessing_AudioStreamingService_createEngine(JNIEnv *env,
                                                                                    jobject thiz) {
    createEngine();
    createOutputMix();
    startAudioStreaming();
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