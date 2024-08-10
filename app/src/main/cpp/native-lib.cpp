#include <jni.h>
#include <string>
//
// Created by georg on 10.08.2024.
//

extern "C"
JNIEXPORT void * JNICALL
Java_com_georgv_audioworkstation_audioprocessing_AudioStreamingService_stringFromJNI(JNIEnv *env,
                                                                                     jobject thiz) {
    // TODO: implement stringFromJNI()
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}