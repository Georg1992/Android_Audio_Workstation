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
	
	// Recording functionality
	bool startRecording(const std::string& outputPath, int32_t sampleRate = 44100, int32_t channels = 2);
	void stopRecording();
	bool isRecording() const { return m_isRecording; }
	void processRecordedAudio(const float* inputBuffer, int32_t numFrames, int32_t channels);

private:
	std::vector<Track> m_tracks;
	bool m_isPlaying = false;
	
	// Recording state
	bool m_isRecording = false;
	std::string m_recordingPath;
	std::vector<float> m_recordingBuffer;
	uint32_t m_recordingSampleRate = 44100;
	uint32_t m_recordingChannels = 2;
	
	// WAV file reading
	std::unique_ptr<WavData> loadWavFile(const std::string& path);
	bool readWavHeader(FILE* file, uint32_t& sampleRate, uint32_t& channels, uint32_t& dataSize);
	
	// WAV file writing
	bool writeWavFile(const std::string& path, const std::vector<float>& samples, uint32_t sampleRate, uint32_t channels);
};

} // namespace dawengine