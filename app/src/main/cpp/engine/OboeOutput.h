#pragma once

#include <oboe/Oboe.h>
#include <cstdint>
#include <memory>

namespace dawengine {
class AudioEngine;
}

class OboeOutput : public oboe::AudioStreamCallback {
public:
	explicit OboeOutput(dawengine::AudioEngine* engine);
	~OboeOutput() override;

	bool start(int32_t sampleRate = 44100, int32_t channelCount = 2);
	void stop();

	oboe::DataCallbackResult onAudioReady(oboe::AudioStream* stream,
										void* audioData,
										int32_t numFrames) override;

private:
	dawengine::AudioEngine* m_engine;
	std::shared_ptr<oboe::AudioStream> m_stream;
};