#pragma once

#include <string>
#include <vector>
#include <cstdint>

namespace dawengine {

struct Track {
	std::string wavPath;
};

class AudioEngine {
public:
	AudioEngine();
	~AudioEngine();

	void clearTracks();
	void addTrack(const std::string &wavPath);

	// Offline mix to a WAV file (16-bit PCM stereo at 44.1kHz)
	// Returns true on success
	bool offlineMixToWav(const std::string &outputPath);

private:
	std::vector<Track> m_tracks;
};

} // namespace dawengine