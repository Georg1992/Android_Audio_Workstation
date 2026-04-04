#include "OboeOutput.h"
#include "AudioEngine.h"

OboeOutput::OboeOutput(dawengine::AudioEngine* engine)
    : m_engine(engine) {}

OboeOutput::~OboeOutput() { stop(); }

bool OboeOutput::start(int32_t sampleRate, int32_t channelCount) {
    stop();

    auto builder = std::make_unique<oboe::AudioStreamBuilder>();

    builder->setFormat(oboe::AudioFormat::Float);
    builder->setSampleRate(sampleRate);
    builder->setChannelCount(channelCount);
    builder->setSharingMode(oboe::SharingMode::Shared);
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
    if (!m_engine || !out) {
        return oboe::DataCallbackResult::Stop;
    }

    m_engine->render(out, numFrames, stream->getChannelCount(), stream->getSampleRate());
    return oboe::DataCallbackResult::Continue;
}