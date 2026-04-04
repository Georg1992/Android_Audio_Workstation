#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

namespace dawengine {

struct Track {
    std::string wavPath;
};

class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    void configureProject(int32_t sampleRate, int32_t fileBitDepth);
    void clearTracks();
    void addTrack(const std::string &wavPath);

    bool startRecording(int32_t channelCount, const std::string &outputPath);
    bool stopRecording();

    bool startPlayback(float gain);
    void setPlaybackGain(float gain);
    bool isPlaybackActive() const;
    void stopPlayback();

    void render(float *outputInterleaved, int32_t numFrames, int32_t channels, int32_t sampleRate);

private:
    struct LoadedWav {
        int32_t sampleRate = 0;
        int32_t channelCount = 0;
        std::vector<float> samples;
    };
    struct PlaybackState {
        int32_t channelCount = 0;
        std::vector<float> samples;
    };

    bool openInputStream(int32_t channelCount);
    void closeInputStream();
    void recordLoop();
    bool writeRecordingToWav(const std::vector<float> &samples, int32_t channelCount, const std::string &outputPath) const;
    bool loadPlaybackTrack();
    static bool readWavFile(const std::string &path, LoadedWav *loadedWav);

    int32_t m_sampleRate = 48'000;
    int32_t m_fileBitDepth = 16;
    std::mutex m_trackMutex;
    std::vector<Track> m_tracks;

    std::mutex m_recordMutex;
    std::vector<float> m_recordedSamples;
    std::string m_recordingOutputPath;
    int32_t m_recordingChannelCount = 1;
    std::shared_ptr<oboe::AudioStream> m_inputStream;
    std::thread m_recordThread;
    std::atomic<bool> m_isRecording = false;

    std::shared_ptr<const PlaybackState> m_playbackState;
    std::atomic<size_t> m_playbackFramePosition = 0;
    std::atomic<float> m_playbackGain = 1.0f;
    std::atomic<bool> m_isPlaying = false;
};

}