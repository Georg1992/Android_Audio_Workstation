#include "OboeOutput.h"
#include "AudioEngine.h"
#include <cmath>

OboeOutput::OboeOutput(dawengine::AudioEngine* engine)
	: m_engine(engine) {}

OboeOutput::~OboeOutput() { stop(); }

bool OboeOutput::start(int32_t sampleRate, int32_t channelCount) {
    auto builder = std::make_unique<oboe::AudioStreamBuilder>();

    builder->setFormat(oboe::AudioFormat::Float);
    builder->setSampleRate(sampleRate);
    builder->setChannelCount(channelCount);
    builder->setSharingMode(oboe::SharingMode::Exclusive);
    builder->setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder->setDirection(oboe::Direction::Output);
    builder->setCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    oboe::Result result = builder->openStream(stream);
    if (result != oboe::Result::OK || !stream) return false;

    m_stream = stream;
    return m_stream->requestStart() == oboe::Result::OK;
}

void OboeOutput::stop() {
	if (m_stream) {
		m_stream->requestStop();
		m_stream->close();
		m_stream.reset();
	}
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream* stream,
										void* audioData,
										int32_t numFrames) {
	float* out = static_cast<float*>(audioData);
	const int32_t channels = stream->getChannelCount();
	// TODO: call m_engine->render(out, numFrames, channels)
	static float phase = 0.0f;
	const float freq = 440.0f;
	const float sr = static_cast<float>(stream->getSampleRate());
	for (int i = 0; i < numFrames; ++i) {
		float s = std::sin(2.0f * M_PI * freq * phase / sr) * 0.1f;
		for (int c = 0; c < channels; ++c) {
			out[i * channels + c] = s;
		}
		phase += 1.0f;
		if (phase > sr) phase -= sr;
	}
	return oboe::DataCallbackResult::Continue;
}