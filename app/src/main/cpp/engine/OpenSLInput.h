#pragma once

#include <SLES/OpenSLES.h>
#include <SLES/OpenSLES_Android.h>
#include <cstdint>

namespace dawengine {
class AudioEngine;
}

class OpenSLInput {
public:
	explicit OpenSLInput(dawengine::AudioEngine* engine);
	~OpenSLInput();

	bool start(int32_t sampleRate = 44100, int32_t channelCount = 2);
	void stop();

private:
	static void recorderCallback(SLAndroidSimpleBufferQueueItf bq, void* context);

	dawengine::AudioEngine* m_engine;
	SLObjectItf m_engineObject = nullptr;
	SLEngineItf m_engine_itf = nullptr;
	SLObjectItf m_recorderObject = nullptr;
	SLRecordItf m_recorderRecord = nullptr;
	SLAndroidSimpleBufferQueueItf m_recorderBufferQueue = nullptr;

	int16_t* m_buffer = nullptr;
	float* m_floatBuffer = nullptr;
	size_t m_bufferSize = 0;
	int32_t m_frames = 0;
	int32_t m_channels = 0;
	int32_t m_sampleRate = 0;
	bool m_recording = false;
};