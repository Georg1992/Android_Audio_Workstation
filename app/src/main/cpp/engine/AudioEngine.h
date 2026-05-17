#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <array>
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
 * Streaming playback engine with a fixed-capacity multi-lane playback skeleton.
 *
 * Recording is unchanged — capture path is separate from playback lanes.
 *
 * Playback uses one dedicated I/O thread that prefetches WAV PCM into per-lane
 * SPSC [RingBuffer]s; the Oboe callback drains them in [render] without locks
 * or heap allocation on the realtime thread.
 *
 * Structural playback mutation invariant:
 *  - caller/JNI must pause the Oboe render consumer with [pauseForSafeEngineMutation]
 *  - AudioEngine stops/joins the I/O producer before touching lane rings/sources
 *
 * Product playback still uses the single-track surface; the multi-lane JNI
 * entry point is native-only test plumbing for validating lanes 0..7.
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
    float recordingInputLevel() const {
        return m_recordingInputLevel.load(std::memory_order_acquire);
    }

    /**
     * Arms playback on lane 0 for `wavPath`. Reuses the open source when the path
     * matches lane 0 (cheap rewind); other lanes remain cleared/deactivated.
     */
    bool setPlaybackSource(const std::string &wavPath, float gain);
    bool setPlaybackSources(const std::vector<std::string> &wavPaths,
                            const std::vector<float> &gains);

    void setPlaybackGain(float gain);
    bool isPlaybackActive() const { return m_isPlaying.load(std::memory_order_acquire); }
    void stopPlayback();

    /** Returns lane 0 source channel count, or 0 if no playback source there. */
    int32_t playbackChannelCount() const {
        return m_playbackLanes[0].srcChannels.load(std::memory_order_acquire);
    }

    /**
     * Tears down the I/O thread and closes playback sources/rings.
     * The output stream is not owned by the engine.
     */
    void releasePlaybackResources();

    /** Oboe realtime callback: sums armed lanes into the interleaved buffer. */
    void render(float *outputInterleaved, int32_t numFrames, int32_t channels, int32_t sampleRate);

private:
    static constexpr std::size_t kPlaybackLaneCount = 16;
    static constexpr std::size_t kPlaybackLaneProductCap = 8;

    struct PlaybackLaneSlot {
        std::shared_ptr<IAudioSource> source;
        std::unique_ptr<RingBuffer> ring;
        std::string currentPath;

        std::atomic<bool> sourceExhausted{false};
        std::atomic<float> gain{1.0f};
        std::atomic<int32_t> srcChannels{0};
    };

    static int32_t computeRingFramesForSampleRate(int32_t sampleRateHz);
    static int32_t computePrerollFramesForSampleRate(int32_t sampleRateHz);

    /** Clears all lanes while holding `m_playbackMutex` after render is paused and I/O has joined. */
    void clearPlaybackLanesLocked();

    /**
     * Strips inactive lanes beyond index 0 (clears lane 1..15). Playback gate must already be cleared.
     * RingBuffer::reset is only applied after render is paused and the I/O producer has joined.
     */
    void deactivateAuxiliaryLanesLocked();

    /**
     * Arm exactly lane 0; clears lanes 1..15. Holds `playbackLock`; caller has already quiesced
     * render + I/O. Returns false if the WAV arm fails before enabling playback.
     */
    bool armSinglePlaybackLaneLocked(const std::string &wavPath, float laneGain);
    bool armPlaybackLanesLocked(const std::vector<std::string> &wavPaths,
                                const std::vector<float> &gains);

    bool openInputStream(int32_t channelCount);
    void closeInputStream();
    void recordLoop();
    bool writeRecordingToWav(const std::vector<float> &samples,
                             int32_t channelCount,
                             const std::string &outputPath) const;

    void ensureIoThreadRunning();
    void stopIoThread();
    void ioLoop();

    void renderMaybeCompletePlaybackMaster(int32_t numFramesOutput,
                                           int32_t outChannels,
                                           int32_t minimumFramesReturnedFromLanes);

    int32_t m_sampleRate = 48'000;
    int32_t m_fileBitDepth = 16;

    std::mutex m_recordMutex;
    std::vector<float> m_recordedSamples;
    std::string m_recordingOutputPath;
    int32_t m_recordingChannelCount = 1;
    std::shared_ptr<oboe::AudioStream> m_inputStream;
    std::thread m_recordThread;
    std::atomic<bool> m_isRecording{false};
    std::atomic<float> m_recordingInputLevel{0.0f};

    std::mutex m_playbackMutex;
    std::array<PlaybackLaneSlot, kPlaybackLaneCount> m_playbackLanes{};
    /** True while the JNI surface expects audible playback prefetch + render mixing. */
    std::atomic<bool> m_isPlaying{false};

    std::thread m_ioThread;
    std::atomic<bool> m_ioRunning{false};

    /** Sized once on JNI; render never resizes — large enough for one lane stereo read burst. */
    std::vector<float> m_renderScratch;
};

} // namespace dawengine
