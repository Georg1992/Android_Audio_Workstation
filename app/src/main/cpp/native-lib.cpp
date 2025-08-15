#include <jni.h>
#include <string>
//
// Created by georg on 10.08.2024.
//

extern "C"
JNIEXPORT jstring JNICALL
Java_com_georgv_audioworkstation_audioprocessing_AudioStreamingService_stringFromJNI(JNIEnv *env,
                                                                                     jobject thiz) {
    std::string hello = "Hello from C++";
    return env->NewStringUTF(hello.c_str());
}