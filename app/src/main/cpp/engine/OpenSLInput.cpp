#include "OpenSLInput.h"
#include "AudioEngine.h"
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "OpenSLInput"

OpenSLInput::OpenSLInput(dawengine::AudioEngine* engine)
	: m_engine(engine) {}

OpenSLInput::~OpenSLInput() {
	stop();
}

bool OpenSLInput::start(int32_t sampleRate, int32_t channelCount) {
	SLresult result;

	// Create engine
	result = slCreateEngine(&m_engineObject, 0, nullptr, 0, nullptr, nullptr);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_engineObject)->Realize(m_engineObject, SL_BOOLEAN_FALSE);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_engineObject)->GetInterface(m_engineObject, SL_IID_ENGINE, &m_engine_itf);
	if (result != SL_RESULT_SUCCESS) return false;

	// Configure audio source
	SLDataLocator_IODevice loc_dev = {SL_DATALOCATOR_IODEVICE, SL_IODEVICE_AUDIOINPUT,
		SL_DEFAULTDEVICEID_AUDIOINPUT, nullptr};
	SLDataSource audioSrc = {&loc_dev, nullptr};

	// Configure audio sink
	SLDataLocator_AndroidSimpleBufferQueue loc_bq = {SL_DATALOCATOR_ANDROIDSIMPLEBUFFERQUEUE, 2};
	SLDataFormat_PCM format_pcm = {
		SL_DATAFORMAT_PCM, static_cast<SLuint32>(channelCount), static_cast<SLuint32>(sampleRate * 1000),
		SL_PCMSAMPLEFORMAT_FIXED_16, SL_PCMSAMPLEFORMAT_FIXED_16,
		channelCount == 2 ? SL_SPEAKER_FRONT_LEFT | SL_SPEAKER_FRONT_RIGHT : SL_SPEAKER_FRONT_CENTER,
		SL_BYTEORDER_LITTLEENDIAN
	};
	SLDataSink audioSnk = {&loc_bq, &format_pcm};

	// Create audio recorder
	const SLInterfaceID ids[2] = {SL_IID_ANDROIDSIMPLEBUFFERQUEUE, SL_IID_ANDROIDCONFIGURATION};
	const SLboolean req[2] = {SL_BOOLEAN_TRUE, SL_BOOLEAN_TRUE};

	result = (*m_engine_itf)->CreateAudioRecorder(m_engine_itf, &m_recorderObject, &audioSrc, &audioSnk, 2, ids, req);
	if (result != SL_RESULT_SUCCESS) return false;

	// Configure for voice recognition (optional, can improve quality)
	SLAndroidConfigurationItf recorderConfig;
	result = (*m_recorderObject)->GetInterface(m_recorderObject, SL_IID_ANDROIDCONFIGURATION, &recorderConfig);
	if (result == SL_RESULT_SUCCESS) {
		SLuint32 presetValue = SL_ANDROID_RECORDING_PRESET_GENERIC;
		(*recorderConfig)->SetConfiguration(recorderConfig, SL_ANDROID_KEY_RECORDING_PRESET,
			&presetValue, sizeof(SLuint32));
	}

	result = (*m_recorderObject)->Realize(m_recorderObject, SL_BOOLEAN_FALSE);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_recorderObject)->GetInterface(m_recorderObject, SL_IID_RECORD, &m_recorderRecord);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_recorderObject)->GetInterface(m_recorderObject, SL_IID_ANDROIDSIMPLEBUFFERQUEUE, &m_recorderBufferQueue);
	if (result != SL_RESULT_SUCCESS) return false;

	result = (*m_recorderBufferQueue)->RegisterCallback(m_recorderBufferQueue, recorderCallback, this);
	if (result != SL_RESULT_SUCCESS) return false;

	// Create buffers
	m_frames = 1024;
	m_channels = channelCount;
	m_sampleRate = sampleRate;
	m_bufferSize = m_frames * m_channels;
	m_buffer = new int16_t[m_bufferSize];
	m_floatBuffer = new float[m_bufferSize];

	// Start recording
	result = (*m_recorderRecord)->SetRecordState(m_recorderRecord, SL_RECORDSTATE_RECORDING);
	if (result != SL_RESULT_SUCCESS) return false;

	m_recording = true;

	// Enqueue initial buffer
	recorderCallback(m_recorderBufferQueue, this);

	return true;
}

void OpenSLInput::stop() {
	m_recording = false;

	if (m_recorderRecord) {
		(*m_recorderRecord)->SetRecordState(m_recorderRecord, SL_RECORDSTATE_STOPPED);
	}
	if (m_recorderObject) {
		(*m_recorderObject)->Destroy(m_recorderObject);
		m_recorderObject = nullptr;
		m_recorderRecord = nullptr;
		m_recorderBufferQueue = nullptr;
	}
	if (m_engineObject) {
		(*m_engineObject)->Destroy(m_engineObject);
		m_engineObject = nullptr;
		m_engine_itf = nullptr;
	}

	delete[] m_buffer;
	delete[] m_floatBuffer;
	m_buffer = nullptr;
	m_floatBuffer = nullptr;
	m_bufferSize = 0;
}

void OpenSLInput::recorderCallback(SLAndroidSimpleBufferQueueItf bq, void* context) {
	auto* self = static_cast<OpenSLInput*>(context);
	if (!self->m_recording || !self->m_buffer || !self->m_engine) return;

	// Convert int16 samples to float
	for (int i = 0; i < self->m_bufferSize; ++i) {
		self->m_floatBuffer[i] = static_cast<float>(self->m_buffer[i]) / 32768.0f;
	}

	// Send audio to AudioEngine for processing/recording
	self->m_engine->processRecordedAudio(self->m_floatBuffer, self->m_frames, self->m_channels);

	// Re-enqueue buffer for next recording chunk
	if (self->m_recording) {
		(*bq)->Enqueue(bq, self->m_buffer, self->m_bufferSize * sizeof(int16_t));
	}
}