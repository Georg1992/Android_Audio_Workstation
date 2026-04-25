#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "RingBuffer.h"

namespace dawengine {

class IAudioSource;

/**
 * Streaming, single-track audio engine.
 *
 * Recording stays the same as before — capture into a pcm buffer, write WAV on
 * stop. Playback now uses a producer/consumer split:
 *
 *  - JNI thread calls [setPlaybackSource], which opens an [IAudioSource] and
 *    arms the engine.
 *  - A dedicated I/O worker thread reads from the source in batches and pushes
 *    PCM frames into a lock-free [RingBuffer].
 *  - The Oboe render callback (audio thread) drains the ring buffer in
 *    [render], with no file I/O and no allocations.
 *
 * The engine deliberately keeps state minimal: a single playable source, no
 * mixer, no time stretching. Multi-track mixing is the next layer of the
 * design and will sit on top of the same source/ring abstraction.
 */
class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    void configureProject(int32_t sampleRate, int32_t fileBitDepth);

    bool startRecording(int32_t channelCount, const std::string &outputPath);
    bool stopRecording();

    /**
     * Arms playback for `wavPath`. Reuses the open source if the path matches
     * the previous one (cheap rewind), otherwise tears down and opens a fresh
     * [LocalWavSource]. Returns true if playback is now armed.
     */
    bool setPlaybackSource(const std::string &wavPath, float gain);

    void setPlaybackGain(float gain);
    bool isPlaybackActive() const { return m_isPlaying.load(std::memory_order_acquire); }
    void stopPlayback();

    /** Returns the source channel count, or 0 if no source is loaded. */
    int32_t playbackChannelCount() const { return m_sourceChannelCount.load(); }

    /**
     * Tears down the I/O thread and closes the source. The output stream is
     * not owned by the engine; the caller is responsible for releasing it.
     * Safe to call multiple times.
     */
    void releasePlaybackResources();

    /** Audio-thread callback. Drains the ring into the interleaved output buffer. */
    void render(float *outputInterleaved, int32_t numFrames, int32_t channels, int32_t sampleRate);

private:
    bool openInputStream(int32_t channelCount);
    void closeInputStream();
    void recordLoop();
    bool writeRecordingToWav(const std::vector<float> &samples,
                             int32_t channelCount,
                             const std::string &outputPath) const;

    void ensureIoThreadRunning();
    void stopIoThread();
    void ioLoop();

    int32_t m_sampleRate = 48'000;
    int32_t m_fileBitDepth = 16;

    std::mutex m_recordMutex;
    std::vector<float> m_recordedSamples;
    std::string m_recordingOutputPath;
    int32_t m_recordingChannelCount = 1;
    std::shared_ptr<oboe::AudioStream> m_inputStream;
    std::thread m_recordThread;
    std::atomic<bool> m_isRecording{false};

    // Playback state. Mutated only from the JNI thread under m_playbackMutex
    // (or from the I/O thread for [m_sourceExhausted]); read concurrently from
    // the audio render thread via the atomics below.
    std::mutex m_playbackMutex;
    std::shared_ptr<IAudioSource> m_source;
    std::string m_currentSourcePath;
    std::unique_ptr<RingBuffer> m_ring;

    std::thread m_ioThread;
    std::atomic<bool> m_ioRunning{false};
    std::atomic<bool> m_isPlaying{false};
    std::atomic<bool> m_sourceExhausted{false};
    std::atomic<float> m_playbackGain{1.0f};
    std::atomic<int32_t> m_sourceChannelCount{0};
};

} // namespace dawengine
