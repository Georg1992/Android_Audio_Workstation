#pragma once

#include <string>
#include <vector>
#include <cstdint>
#include <memory>

namespace dawengine {

struct WavData {
	std::vector<float> samples; // interleaved stereo
	uint32_t sampleRate;
	uint32_t channels;
	uint32_t totalFrames;
};

struct Track {
	std::string wavPath;
	std::unique_ptr<WavData> wavData;
	float volume = 1.0f;
	uint32_t playbackPosition = 0; // in frames
	bool isLoaded = false;
};

class AudioEngine {
public:
	AudioEngine();
	~AudioEngine();

	void clearTracks();
	void addTrack(const std::string &wavPath, float volume = 1.0f);
	
	// Load all track WAV files into memory
	void loadAllTracks();

	// Offline mix to a WAV file (16-bit PCM stereo at 44.1kHz)
	// Returns true on success
	bool offlineMixToWav(const std::string &outputPath);

	// Real-time render into provided interleaved float buffer
	void render(float* outputInterleaved, int32_t numFrames, int32_t channels, int32_t sampleRate);
	
	// Playback control
	void start();
	void stop();
	void reset(); // reset all playback positions to start

private:
	std::vector<Track> m_tracks;
	bool m_isPlaying = false;
	
	// WAV file reading
	std::unique_ptr<WavData> loadWavFile(const std::string& path);
	bool readWavHeader(FILE* file, uint32_t& sampleRate, uint32_t& channels, uint32_t& dataSize);
};

} // namespace dawengine