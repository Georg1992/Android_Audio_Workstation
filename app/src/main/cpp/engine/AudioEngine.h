#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <array>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "PlaybackLaneLifecycle.h"
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
 *    on full session rebuild (setPlaybackSources / stop / release)
 *
 * HJ.2 hot-join mutates only inactive slots via staging + atomic lifecycle publish;
 * render never takes m_playbackMutex.
 */
class AudioEngine {
public:
    AudioEngine();
    ~AudioEngine();

    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    void configureProject(int32_t sampleRate, int32_t fileBitDepth);

    /**
     * @param startPositionMs Timeline offset for [transportFrame] when not already playing.
     *        Ignored while [isPlaybackActive] (play+record keeps playback-initialized transport).
     */
    bool startRecording(int32_t channelCount,
                        const std::string &outputPath,
                        int64_t startPositionMs = 0);
    bool stopRecording();
    float recordingInputLevel() const {
        return m_recordingInputLevel.load(std::memory_order_acquire);
    }

    bool setPlaybackSource(const std::string &wavPath,
                           float gain,
                           int64_t startPositionMs = 0,
                           int64_t sessionTimelineEndMs = 0);
    bool setPlaybackSources(const std::vector<std::string> &wavPaths,
                            const std::vector<float> &gains,
                            int64_t startPositionMs = 0,
                            int64_t sessionTimelineEndMs = 0,
                            const std::vector<int64_t> &laneClipStartMs = {},
                            const std::vector<int64_t> &laneClipDurationMs = {});

    void setPlaybackGain(float gain);

    /** HJ.1 live audibility; lane must be [PlaybackLaneLifecycle::Active]. */
    void setPlaybackLaneAudible(std::size_t laneIndex, bool audible);

    /**
     * HJ.2: reserve a fixed slot and enqueue async prepare+commit. Returns lane index (0..7) or -1.
     * Safe while Oboe is running; does not rebuild the session.
     */
    int32_t beginHotJoinLane(const std::string &wavPath,
                             float gain,
                             int64_t clipStartMs = 0,
                             int64_t clipDurationMs = 0);

    /** HJ.2: cancel a preparing/ready lane; no-op for active session lanes (use [setPlaybackLaneAudible]). */
    void cancelHotJoinLane(std::size_t laneIndex);

    PlaybackLaneLifecycle laneLifecycle(std::size_t laneIndex) const;

    bool isPlaybackActive() const { return m_isPlaying.load(std::memory_order_acquire); }
    void stopPlayback();

    /**
     * Sample-domain timeline position for active native transport (Clock.2).
     * [transportStartFrame] / [transportFrame] are set when playback arms.
     * [transportFrame] is advanced by output [render] while [isPlaybackActive] (HJ.0/HJ.2).
     * Clock.3 will add recording-only advancement on the same counters.
     */
    int64_t transportStartFrame() const {
        return m_masterPlaybackStartFrame.load(std::memory_order_acquire);
    }

    int64_t transportFrame() const {
        return m_masterPlaybackFrame.load(std::memory_order_acquire);
    }

    int64_t transportPositionMs() const;

    int32_t playbackChannelCount() const {
        return m_playbackLanes[0].srcChannels.load(std::memory_order_acquire);
    }

    void releasePlaybackResources();

    void render(float *outputInterleaved, int32_t numFrames, int32_t channels, int32_t sampleRate);

private:
    static constexpr std::size_t kPlaybackLaneCount = 16;
    static constexpr std::size_t kPlaybackLaneProductCap = 8;

    struct PlaybackLaneSlot {
        std::shared_ptr<IAudioSource> source;
        std::unique_ptr<RingBuffer> ring;
        std::string currentPath;

        std::atomic<PlaybackLaneLifecycle> lifecycle{PlaybackLaneLifecycle::Inactive};
        std::atomic<bool> sourceExhausted{false};
        std::atomic<bool> audibleEnabled{true};
        /** Cleared by [cancelHotJoinLane]; commit publishes audible only if still true (HJ.2). */
        std::atomic<bool> hotJoinPublishAudible{true};
        std::atomic<float> gain{1.0f};
        std::atomic<int32_t> srcChannels{0};
        std::atomic<int64_t> clipTimelineStartFrame{0};
        /** 0 = no explicit timeline clip end (lane active until WAV exhausted). */
        std::atomic<int64_t> clipTimelineEndFrame{0};
    };

    struct HotJoinStagingSlot {
        std::shared_ptr<IAudioSource> source;
        std::unique_ptr<RingBuffer> ring;
        std::string path;
        float gain = 1.0f;
        int32_t channels = 0;
        int64_t clipStartMs = 0;
        int64_t clipDurationMs = 0;
    };

    struct HotJoinWorkItem {
        std::size_t laneIndex = 0;
        std::string wavPath;
        float gain = 1.0f;
        int64_t clipStartMs = 0;
        int64_t clipDurationMs = 0;
    };

    static int32_t computeRingFramesForSampleRate(int32_t sampleRateHz);
    static int32_t computePrerollFramesForSampleRate(int32_t sampleRateHz);

    void clearPlaybackLanesLocked();
    void deactivateAuxiliaryLanesLocked();
    void setLaneInactiveLocked(PlaybackLaneSlot &lane);

    bool armOnePlaybackLaneLocked(std::size_t laneIndex,
                                  const std::string &wavPath,
                                  float laneGain,
                                  int64_t transportStartFrame,
                                  int64_t clipStartMs,
                                  int64_t clipDurationMs,
                                  bool reuseExistingSourceOnSamePath);
    bool armPlaybackLanesLocked(const std::vector<std::string> &wavPaths,
                                const std::vector<float> &gains,
                                int64_t transportStartFrame,
                                const std::vector<int64_t> &laneClipStartMs,
                                const std::vector<int64_t> &laneClipDurationMs);

    bool openInputStream(int32_t channelCount);
    void closeInputStream();
    void recordLoop();
    bool writeRecordingToWav(const std::vector<float> &samples,
                             int32_t channelCount,
                             const std::string &outputPath) const;

    void ensureIoThreadRunning();
    void stopIoThread();
    void ioLoop();

    void ensureHotJoinThreadRunning();
    void stopHotJoinThread();
    void hotJoinThreadLoop();
    void enqueueHotJoinWork(HotJoinWorkItem item);
    bool prepareHotJoinStagingLocked(std::size_t laneIndex, const HotJoinWorkItem &item);
    bool commitHotJoinLaneLocked(std::size_t laneIndex);
    void clearHotJoinStagingLocked(std::size_t laneIndex);

    void renderMaybeCompletePlaybackMaster(int32_t numFramesOutput,
                                           int32_t outChannels,
                                           int32_t minimumFramesReturnedFromLanes);

    void initializeMasterPlaybackTimeline(int64_t startFrame);
    void resetMasterPlaybackTimeline();

    PlaybackLaneLifecycle loadLaneLifecycle(std::size_t laneIndex) const;

    int32_t m_sampleRate = 44'100;
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
    std::array<HotJoinStagingSlot, kPlaybackLaneProductCap> m_hotJoinStaging{};

    std::mutex m_hotJoinMutex;
    std::condition_variable m_hotJoinCv;
    std::deque<HotJoinWorkItem> m_hotJoinQueue;
    std::thread m_hotJoinThread;
    std::atomic<bool> m_hotJoinRunning{false};

    std::atomic<bool> m_isPlaying{false};

    /** Internal storage for [transportStartFrame] / [transportFrame] (Clock.2 naming). */
    alignas(8) std::atomic<int64_t> m_masterPlaybackStartFrame{0};
    alignas(8) std::atomic<int64_t> m_masterPlaybackFrame{0};
    /** Absolute timeline end frame; 0 = complete on lane drain (legacy tests). */
    alignas(8) std::atomic<int64_t> m_playbackSessionEndFrame{0};

    std::thread m_ioThread;
    std::atomic<bool> m_ioRunning{false};

    std::vector<float> m_renderScratch;
};

} // namespace dawengine
