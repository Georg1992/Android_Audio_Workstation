#include "AudioEngine.h"
#include <cstdio>

namespace dawengine {

AudioEngine::AudioEngine() = default;
AudioEngine::~AudioEngine() = default;

void AudioEngine::clearTracks() {
	m_tracks.clear();
}

void AudioEngine::addTrack(const std::string &wavPath) {
	m_tracks.push_back(Track{wavPath});
}

bool AudioEngine::offlineMixToWav(const std::string &outputPath) {
	if (m_tracks.empty()) {
		return false;
	}
	// TODO: Implement proper WAV reading and mixing. For now, just touch the file.
	FILE *f = std::fopen(outputPath.c_str(), "wb");
	if (!f) return false;
	const char *msg = "RIFF....WAVE"; // placeholder bytes
	std::fwrite(msg, 1, 12, f);
	std::fclose(f);
	return true;
}

} // namespace dawengine