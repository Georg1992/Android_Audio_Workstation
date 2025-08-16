#include "AudioEngine.h"
#include <cstdio>
#include <cstring>
#include <android/log.h>
#include <algorithm>

#define LOG_TAG "AudioEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace dawengine {

AudioEngine::AudioEngine() = default;
AudioEngine::~AudioEngine() = default;

void AudioEngine::clearTracks() {
	m_tracks.clear();
}

void AudioEngine::addTrack(const std::string &wavPath, float volume) {
	Track track;
	track.wavPath = wavPath;
	track.volume = volume;
	track.playbackPosition = 0;
	track.isLoaded = false;
	m_tracks.push_back(std::move(track));
	LOGI("Added track: %s", wavPath.c_str());
}

void AudioEngine::loadAllTracks() {
	for (auto& track : m_tracks) {
		if (!track.isLoaded) {
			track.wavData = loadWavFile(track.wavPath);
			track.isLoaded = (track.wavData != nullptr);
			if (track.isLoaded) {
				LOGI("Loaded track: %s (%d frames, %d channels, %d Hz)", 
					track.wavPath.c_str(), track.wavData->totalFrames, 
					track.wavData->channels, track.wavData->sampleRate);
			} else {
				LOGE("Failed to load track: %s", track.wavPath.c_str());
			}
		}
	}
}

void AudioEngine::start() {
	m_isPlaying = true;
	LOGI("Playback started");
}

void AudioEngine::stop() {
	m_isPlaying = false;
	LOGI("Playback stopped");
}

void AudioEngine::reset() {
	for (auto& track : m_tracks) {
		track.playbackPosition = 0;
	}
	LOGI("Playback positions reset");
}

bool AudioEngine::offlineMixToWav(const std::string &outputPath) {
	if (m_tracks.empty()) {
		LOGE("No tracks to mix");
		return false;
	}
	
	// Load all tracks first
	loadAllTracks();
	
	// Find the longest track duration
	uint32_t maxFrames = 0;
	uint32_t outputSampleRate = 44100;
	
	for (const auto& track : m_tracks) {
		if (track.isLoaded && track.wavData) {
			maxFrames = std::max(maxFrames, track.wavData->totalFrames);
			outputSampleRate = track.wavData->sampleRate; // Use sample rate from first loaded track
		}
	}
	
	if (maxFrames == 0) {
		LOGE("No valid tracks loaded for mixing");
		return false;
	}
	
	// Create output buffer (stereo)
	std::vector<float> mixBuffer(maxFrames * 2, 0.0f);
	
	// Mix all tracks
	for (const auto& track : m_tracks) {
		if (!track.isLoaded || !track.wavData) continue;
		
		const auto& wav = track.wavData;
		uint32_t framesToMix = std::min(maxFrames, wav->totalFrames);
		
		for (uint32_t frame = 0; frame < framesToMix; ++frame) {
			for (uint32_t ch = 0; ch < 2; ++ch) { // Always output stereo
				float sample = 0.0f;
				
				if (wav->channels == 1) {
					// Mono source: duplicate to both channels
					sample = wav->samples[frame] * track.volume;
				} else if (wav->channels == 2) {
					// Stereo source
					sample = wav->samples[frame * 2 + ch] * track.volume;
				}
				
				mixBuffer[frame * 2 + ch] += sample;
			}
		}
	}
	
	// Write WAV file
	FILE* file = std::fopen(outputPath.c_str(), "wb");
	if (!file) {
		LOGE("Failed to create output file: %s", outputPath.c_str());
		return false;
	}
	
	// WAV header
	uint32_t dataSize = maxFrames * 2 * sizeof(int16_t);
	uint32_t fileSize = 36 + dataSize;
	
	std::fwrite("RIFF", 1, 4, file);
	std::fwrite(&fileSize, 4, 1, file);
	std::fwrite("WAVE", 1, 4, file);
	std::fwrite("fmt ", 1, 4, file);
	
	uint32_t fmtSize = 16;
	uint16_t audioFormat = 1; // PCM
	uint16_t channels = 2;
	uint32_t byteRate = outputSampleRate * channels * 2;
	uint16_t blockAlign = channels * 2;
	uint16_t bitsPerSample = 16;
	
	std::fwrite(&fmtSize, 4, 1, file);
	std::fwrite(&audioFormat, 2, 1, file);
	std::fwrite(&channels, 2, 1, file);
	std::fwrite(&outputSampleRate, 4, 1, file);
	std::fwrite(&byteRate, 4, 1, file);
	std::fwrite(&blockAlign, 2, 1, file);
	std::fwrite(&bitsPerSample, 2, 1, file);
	
	std::fwrite("data", 1, 4, file);
	std::fwrite(&dataSize, 4, 1, file);
	
	// Convert float samples to int16 and write
	for (float sample : mixBuffer) {
		// Clamp and convert to int16
		sample = std::max(-1.0f, std::min(1.0f, sample));
		int16_t intSample = static_cast<int16_t>(sample * 32767.0f);
		std::fwrite(&intSample, 2, 1, file);
	}
	
	std::fclose(file);
	LOGI("Mixed audio saved to: %s (%d frames)", outputPath.c_str(), maxFrames);
	return true;
}

void AudioEngine::render(float* outputInterleaved, int32_t numFrames, int32_t channels, int32_t /*sampleRate*/) {
	if (!outputInterleaved || !m_isPlaying) {
		// Fill with silence
		std::memset(outputInterleaved, 0, sizeof(float) * numFrames * channels);
		return;
	}
	
	// Clear output buffer
	std::memset(outputInterleaved, 0, sizeof(float) * numFrames * channels);
	
	// Mix all loaded tracks
	for (auto& track : m_tracks) {
		if (!track.isLoaded || !track.wavData) continue;
		
		const auto& wav = track.wavData;
		
		for (int32_t frame = 0; frame < numFrames; ++frame) {
			if (track.playbackPosition >= wav->totalFrames) {
				// Track finished, could loop here
				continue;
			}
			
			for (int32_t ch = 0; ch < channels && ch < 2; ++ch) {
				float sample = 0.0f;
				
				if (wav->channels == 1) {
					// Mono source: duplicate to both channels
					sample = wav->samples[track.playbackPosition] * track.volume;
				} else if (wav->channels == 2) {
					// Stereo source
					sample = wav->samples[track.playbackPosition * 2 + ch] * track.volume;
				}
				
				outputInterleaved[frame * channels + ch] += sample;
			}
			
			track.playbackPosition++;
		}
	}
}

std::unique_ptr<WavData> AudioEngine::loadWavFile(const std::string& path) {
	FILE* file = std::fopen(path.c_str(), "rb");
	if (!file) {
		LOGE("Failed to open WAV file: %s", path.c_str());
		return nullptr;
	}
	
	uint32_t sampleRate, channels, dataSize;
	if (!readWavHeader(file, sampleRate, channels, dataSize)) {
		std::fclose(file);
		return nullptr;
	}
	
	// Calculate number of samples and frames
	uint32_t bytesPerSample = 2; // Assuming 16-bit PCM
	uint32_t totalSamples = dataSize / bytesPerSample;
	uint32_t totalFrames = totalSamples / channels;
	
	auto wavData = std::make_unique<WavData>();
	wavData->sampleRate = sampleRate;
	wavData->channels = channels;
	wavData->totalFrames = totalFrames;
	wavData->samples.resize(totalSamples);
	
	// Read and convert 16-bit PCM to float
	std::vector<int16_t> intSamples(totalSamples);
	size_t samplesRead = std::fread(intSamples.data(), sizeof(int16_t), totalSamples, file);
	
	if (samplesRead != totalSamples) {
		LOGE("Warning: Read %zu samples, expected %u", samplesRead, totalSamples);
	}
	
	// Convert to float [-1.0, 1.0]
	for (size_t i = 0; i < samplesRead; ++i) {
		wavData->samples[i] = static_cast<float>(intSamples[i]) / 32768.0f;
	}
	
	std::fclose(file);
	return wavData;
}

bool AudioEngine::readWavHeader(FILE* file, uint32_t& sampleRate, uint32_t& channels, uint32_t& dataSize) {
	char header[12];
	if (std::fread(header, 1, 12, file) != 12) {
		LOGE("Failed to read WAV header");
		return false;
	}
	
	if (std::memcmp(header, "RIFF", 4) != 0 || std::memcmp(header + 8, "WAVE", 4) != 0) {
		LOGE("Invalid WAV file format");
		return false;
	}
	
	// Find fmt chunk
	char chunkId[4];
	uint32_t chunkSize;
	
	while (std::fread(chunkId, 1, 4, file) == 4 && std::fread(&chunkSize, 4, 1, file) == 1) {
		if (std::memcmp(chunkId, "fmt ", 4) == 0) {
			uint16_t audioFormat, numChannels, bitsPerSample;
			uint32_t byteRate;
			uint16_t blockAlign;
			
			std::fread(&audioFormat, 2, 1, file);
			std::fread(&numChannels, 2, 1, file);
			std::fread(&sampleRate, 4, 1, file);
			std::fread(&byteRate, 4, 1, file);
			std::fread(&blockAlign, 2, 1, file);
			std::fread(&bitsPerSample, 2, 1, file);
			
			if (audioFormat != 1) {
				LOGE("Unsupported audio format: %d (only PCM supported)", audioFormat);
				return false;
			}
			
			if (bitsPerSample != 16) {
				LOGE("Unsupported bit depth: %d (only 16-bit supported)", bitsPerSample);
				return false;
			}
			
			channels = numChannels;
			
			// Skip remaining fmt data
			if (chunkSize > 16) {
				std::fseek(file, chunkSize - 16, SEEK_CUR);
			}
		} else if (std::memcmp(chunkId, "data", 4) == 0) {
			dataSize = chunkSize;
			return true; // Found data chunk, ready to read samples
		} else {
			// Skip unknown chunk
			std::fseek(file, chunkSize, SEEK_CUR);
		}
	}
	
	LOGE("Data chunk not found in WAV file");
	return false;
}

bool AudioEngine::startRecording(const std::string& outputPath, int32_t sampleRate, int32_t channels) {
	if (m_isRecording) {
		LOGE("Recording already in progress");
		return false;
	}
	
	m_recordingPath = outputPath;
	m_recordingSampleRate = sampleRate;
	m_recordingChannels = channels;
	m_recordingBuffer.clear();
	m_isRecording = true;
	
	LOGI("Started recording to: %s (%d Hz, %d channels)", outputPath.c_str(), sampleRate, channels);
	return true;
}

void AudioEngine::stopRecording() {
	if (!m_isRecording) {
		LOGE("No recording in progress");
		return;
	}
	
	m_isRecording = false;
	
	// Save recorded audio to WAV file
	if (!m_recordingBuffer.empty()) {
		if (writeWavFile(m_recordingPath, m_recordingBuffer, m_recordingSampleRate, m_recordingChannels)) {
			LOGI("Recording saved: %s (%zu samples)", m_recordingPath.c_str(), m_recordingBuffer.size());
		} else {
			LOGE("Failed to save recording: %s", m_recordingPath.c_str());
		}
	}
	
	m_recordingBuffer.clear();
}

void AudioEngine::processRecordedAudio(const float* inputBuffer, int32_t numFrames, int32_t channels) {
	if (!m_isRecording || !inputBuffer) return;
	
	// Append audio data to recording buffer
	size_t samplesToAdd = numFrames * channels;
	size_t oldSize = m_recordingBuffer.size();
	m_recordingBuffer.resize(oldSize + samplesToAdd);
	
	std::memcpy(m_recordingBuffer.data() + oldSize, inputBuffer, samplesToAdd * sizeof(float));
}

bool AudioEngine::writeWavFile(const std::string& path, const std::vector<float>& samples, uint32_t sampleRate, uint32_t channels) {
	if (samples.empty()) {
		LOGE("No audio data to write");
		return false;
	}
	
	FILE* file = std::fopen(path.c_str(), "wb");
	if (!file) {
		LOGE("Failed to create WAV file: %s", path.c_str());
		return false;
	}
	
	uint32_t totalFrames = samples.size() / channels;
	uint32_t dataSize = totalFrames * channels * sizeof(int16_t);
	uint32_t fileSize = 36 + dataSize;
	
	// Write WAV header
	std::fwrite("RIFF", 1, 4, file);
	std::fwrite(&fileSize, 4, 1, file);
	std::fwrite("WAVE", 1, 4, file);
	std::fwrite("fmt ", 1, 4, file);
	
	uint32_t fmtSize = 16;
	uint16_t audioFormat = 1; // PCM
	uint16_t numChannels = static_cast<uint16_t>(channels);
	uint32_t byteRate = sampleRate * channels * 2;
	uint16_t blockAlign = static_cast<uint16_t>(channels * 2);
	uint16_t bitsPerSample = 16;
	
	std::fwrite(&fmtSize, 4, 1, file);
	std::fwrite(&audioFormat, 2, 1, file);
	std::fwrite(&numChannels, 2, 1, file);
	std::fwrite(&sampleRate, 4, 1, file);
	std::fwrite(&byteRate, 4, 1, file);
	std::fwrite(&blockAlign, 2, 1, file);
	std::fwrite(&bitsPerSample, 2, 1, file);
	
	std::fwrite("data", 1, 4, file);
	std::fwrite(&dataSize, 4, 1, file);
	
	// Convert float samples to int16 and write
	for (float sample : samples) {
		// Clamp and convert to int16
		sample = std::max(-1.0f, std::min(1.0f, sample));
		int16_t intSample = static_cast<int16_t>(sample * 32767.0f);
		std::fwrite(&intSample, 2, 1, file);
	}
	
	std::fclose(file);
	return true;
}

} // namespace dawengine