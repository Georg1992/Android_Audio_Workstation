#include <jni.h>
#include <memory>
#include <string>
#include "AudioEngine.h"
#include "OpenSLOutput.h"

static std::unique_ptr<dawengine::AudioEngine> g_engine;
static std::unique_ptr<OpenSLOutput> g_output;

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeInit(JNIEnv*, jobject){
	if(!g_engine){
		g_engine = std::make_unique<dawengine::AudioEngine>();
	}
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeRelease(JNIEnv*, jobject){
	if (g_output) { g_output->stop(); g_output.reset(); }
	g_engine.reset();
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeClearTracks(JNIEnv*, jobject){
	if(g_engine){ g_engine->clearTracks(); }
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeAddTrack(JNIEnv* env, jobject, jstring path){
	if(!g_engine) return;
	const char* cpath = env->GetStringUTFChars(path, nullptr);
	g_engine->addTrack(std::string(cpath ? cpath : ""), 1.0f);
	env->ReleaseStringUTFChars(path, cpath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeAddTrackWithVolume(JNIEnv* env, jobject, jstring path, jfloat volume){
	if(!g_engine) return;
	const char* cpath = env->GetStringUTFChars(path, nullptr);
	g_engine->addTrack(std::string(cpath ? cpath : ""), volume);
	env->ReleaseStringUTFChars(path, cpath);
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeLoadTracks(JNIEnv*, jobject){
	if(g_engine){ g_engine->loadAllTracks(); }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeOfflineMixToWav(JNIEnv* env, jobject, jstring outPath){
	if(!g_engine) return JNI_FALSE;
	const char* cout = env->GetStringUTFChars(outPath, nullptr);
	bool ok = g_engine->offlineMixToWav(std::string(cout ? cout : ""));
	env->ReleaseStringUTFChars(outPath, cout);
	return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStart(JNIEnv*, jobject){
	if(!g_engine) return JNI_FALSE;
	if(!g_output) g_output = std::make_unique<OpenSLOutput>(g_engine.get());
	
	g_engine->start();
	return g_output->start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeStop(JNIEnv*, jobject){
	if(g_engine) g_engine->stop();
	if(g_output) g_output->stop();
}

extern "C" JNIEXPORT void JNICALL
Java_com_georgv_audioworkstation_engine_NativeEngine_nativeReset(JNIEnv*, jobject){
	if(g_engine) g_engine->reset();
}