#include "OpenSLOutput.h"
#include "AudioEngine.h"
#include <cmath>
#include <cstring>
#include <android/log.h>

#define LOG_TAG "OpenSLOutput"

OpenSLOutput::OpenSLOutput(dawengine::AudioEngine* engine)
	: m_engine(engine) {}

OpenSLOutput::~OpenSLOutput() { 
	stop(); 
}

bool OpenSLOutput::start(int32_t sampleRate, int32_t channelCount) {
	SLresult result;

	// Create engine
	result = slCreateEngine(&m_engineObject, 0, nullptr, 0, nullptr, nullptr);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_engineObject)->Realize(m_engineObject, SL_BOOLEAN_FALSE);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_engineObject)->GetInterface(m_engineObject, SL_IID_ENGINE, &m_engine_itf);
	if (result != SL_RESULT_SUCCESS) return false;

	// Create output mix
	result = (*m_engine_itf)->CreateOutputMix(m_engine_itf, &m_outputMixObject, 0, nullptr, nullptr);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_outputMixObject)->Realize(m_outputMixObject, SL_BOOLEAN_FALSE);
	if (result != SL_RESULT_SUCCESS) return false;

	// Create audio player
	SLDataLocator_AndroidSimpleBufferQueue loc_bufq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
	SLDataFormat_PCM format_pcm = {
		SL_DATAFORMAT_PCM, static_cast<SLuint32>(channelCount), static_cast<SLuint32>(sampleRate * 1000),
		SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
		channelCount == 2 ? SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT : SL_SPEAKER_FRONT_CENTER,
		SL_BYTEORDER_LITTLEENDIAN
	};
	SLDataSource audioSrc = {&loc_bufq, &format_pcm};

	SLDataLocator_OutputMix loc_outmix = {SL_DATALOCATOR_OUTPUTMIX, m_outputMixObject};
	SLDataSink audioSnk = {&loc_outmix, nullptr};

	const SLInterfaceID ids[2] = {SL_IID_BUFFERQUEUE, SL_IID_PLAY};
	const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};

	result = (*m_engine_itf)->CreateAudioPlayer(m_engine_itf, &m_playerObject, &audioSrc, &audioSnk, 2, ids, req);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_playerObject)->Realize(m_playerObject, SL_BOOLEAN_FALSE);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_playerObject)->GetInterface(m_playerObject, SL_IID_PLAY, &m_playerPlay);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_playerObject)->GetInterface(m_playerObject, SL_IID_BUFFERQUEUE, &m_playerBufferQueue);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_playerBufferQueue)->RegisterCallback(m_playerBufferQueue, playerCallback, this);
	if (result != SL_RESULT_SUCCESS) return false;

	// Create buffer
	m_bufferSize = 1024 * channelCount; // frames * channels
	m_buffer = new int16_t[m_bufferSize];

	// Start playback
	result = (*m_playerPlay)->SetPlayState(m_playerPlay, SL_PLAYSTATE_PLAYING);
	if (result != SL_RESULT_SUCCESS) return false;

	m_playing = true;
	
	// Enqueue initial buffer
	playerCallback(m_playerBufferQueue, this);

	return true;
}

void OpenSLOutput::stop() {
	m_playing = false;

	if (m_playerPlay) {
		(*m_playerPlay)->SetPlayState(m_playerPlay, SL_PLAYSTATE_STOPPED);
	}
	if (m_playerObject) {
		(*m_playerObject)->Destroy(m_playerObject);
		m_playerObject = nullptr;
		m_playerPlay = nullptr;
		m_playerBufferQueue = nullptr;
	}
	if (m_outputMixObject) {
		(*m_outputMixObject)->Destroy(m_outputMixObject);
		m_outputMixObject = nullptr;
	}
	if (m_engineObject) {
		(*m_engineObject)->Destroy(m_engineObject);
		m_engineObject = nullptr;
		m_engine_itf = nullptr;
	}

	delete[] m_buffer;
	m_buffer = nullptr;
	m_bufferSize = 0;
}

void OpenSLOutput::playerCallback(SLAndroidSimpleBufferQueueItf bq, void* context) {
	auto* self = static_cast<OpenSLOutput*>(context);
	if (!self->m_playing || !self->m_buffer) return;

	// Simple test tone generation - 440Hz
	static float phase = 0.0f;
	const float freq = 440.0f;
	const float sr = 44100.0f;
	const int channels = 2;
	const int frames = static_cast<int>(self->m_bufferSize) / channels;

	for (int i = 0; i < frames; ++i) {
		float sample = std::sin(2.0f * M_PI * freq * phase / sr) * 0.1f;
		int16_t sampleInt = static_cast<int16_t>(sample * 32767.0f);
		for (int c = 0; c < channels; ++c) {
			self->m_buffer[i * channels + c] = sampleInt;
		}
		phase += 1.0f;
		if (phase > sr) phase -= sr;
	}

	(*bq)->Enqueue(bq, self->m_buffer, self->m_bufferSize * sizeof(int16_t));
}