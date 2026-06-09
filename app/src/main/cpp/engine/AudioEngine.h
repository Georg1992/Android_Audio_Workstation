#pragma once

#include <oboe/Oboe.h>
#include <atomic>
#include <array>
#include <condition_variable>
#include <cstdint>
#include <deque>
#include <memory>
#include <mutex>
#include <functional>
#include <string>
#include <thread>
#include <vector>

#include "DurationUsStats.h"
#include "PlaybackLaneLifecycle.h"
#include "RingBuffer.h"
#include "TransportClockAnchor.h"

namespace dawengine {

class IAudioSource;

/**
 * Streaming playback engine with a fixed-capacity multi-lane playback skeleton.
 *
 * Recording is unchanged — capture path is separate from playback lanes.
 *
 * Playback uses one dedicated I/O thread that prefetches WAV PCM into per-lane
 * SPSC [RingBuffer]s; the Oboe callback drains them in [render] without
 * [m_playbackMutex]. Lane rings use [std::atomic_load] / [std::atomic_store] on the
 * [std::shared_ptr] handle so snapshots are race-free (C++17); the local copy pins
 * [RingBuffer] lifetime for the duration of each read/write.
 *
 * Structural playback mutation invariant:
 *  - caller/JNI must pause the Oboe render consumer with [pauseForSafeEngineMutation]
 *  - AudioEngine stops/joins the I/O producer before touching lane rings/sources
 *    on full session rebuild (setPlaybackSources / stop / release)
 *
 * HJ.2 hot-join mutates only inactive slots via staging + atomic lifecycle publish;
 * [render] never takes [m_playbackMutex] (refcount snapshot only on the audio thread).
 */
class AudioEngine {
public:
    static constexpr int64_t kRecordingFirstSampleTransportUnset = -1;

    AudioEngine();
    ~AudioEngine() noexcept;

    AudioEngine(const AudioEngine &) = delete;
    AudioEngine &operator=(const AudioEngine &) = delete;

    void configureProject(int32_t sampleRate, int32_t fileBitDepth);

    void setSessionInputRouteKey(const std::string &routeKey);

    int32_t sampleRateForDiagnostics() const { return m_sampleRate; }

    int32_t fileBitDepthForDiagnostics() const { return m_fileBitDepth; }

    void setSessionTransportLatenciesNs(int64_t inputLatencyNs, int64_t outputLatencyNs);

    transport_clock::TransportClockAnchor transportClockAnchor() const;

    int64_t sessionInputLatencyNs() const {
        return m_sessionInputLatencyNs.load(std::memory_order_acquire);
    }

    int64_t sessionOutputLatencyNs() const {
        return m_sessionOutputLatencyNs.load(std::memory_order_acquire);
    }

    /** Query live HAL output latency from the running Oboe stream (callback thread). */
    void refreshLiveOutputLatencyFromStream(oboe::AudioStream *stream);

    /** Live HAL when available, else session profile hint from Kotlin; 0 disables render-ahead. */
    int64_t effectiveOutputLatencyNs() const;

    int64_t outputLatencyFrames() const;

    /**
     * Timeline frame to mix for PCM submitted at [monotonicNs]: transport now + output latency so
     * audible output aligns with the live transport clock.
     */
    int64_t mixTransportFrameAtMonotonicNs(int64_t monotonicNs) const;

    /**
     * @param startPositionMs Timeline offset for [transportFrame] when not already playing.
     *        Ignored while [isPlaybackActive] (play+record keeps playback-initialized transport).
     */
    bool startRecording(int32_t channelCount,
                        const std::string &outputPath,
                        int64_t startPositionMs = 0);

    /**
     * Arms overdub lanes at [startPositionMs], opens input capture, and starts shared transport
     * only when the first input frame arrives (same timeline frame as WAV sample 0).
     */
    bool startOverdubRecordingSession(const std::vector<std::string> &wavPaths,
                                      const std::vector<float> &gains,
                                      int64_t startPositionMs,
                                      int64_t sessionTimelineEndMs,
                                      const std::vector<int64_t> &laneClipStartMs,
                                      const std::vector<int64_t> &laneClipDurationMs,
                                      const std::vector<uint8_t> &laneLoopEnabled,
                                      const std::vector<int64_t> &laneLoopSourceStartMs,
                                      const std::vector<int64_t> &laneLoopSourceEndMs,
                                      const std::vector<int64_t> &laneSourceTrimStartMs,
                                      const std::vector<float> &lanePan,
                                      int32_t channelCount,
                                      const std::string &recordingOutputPath);

    /** Arms deferred overdub playback lanes only (no input capture). */
    bool armOverdubPlaybackSession(const std::vector<std::string> &wavPaths,
                                   const std::vector<float> &gains,
                                   int64_t startPositionMs,
                                   int64_t sessionTimelineEndMs,
                                   const std::vector<int64_t> &laneClipStartMs,
                                   const std::vector<int64_t> &laneClipDurationMs,
                                   const std::vector<uint8_t> &laneLoopEnabled,
                                   const std::vector<int64_t> &laneLoopSourceStartMs,
                                   const std::vector<int64_t> &laneLoopSourceEndMs,
                                   const std::vector<int64_t> &laneSourceTrimStartMs,
                                   const std::vector<float> &lanePan);

    void markOverdubJniReady();

    int64_t overdubJniReadySteadyNs() const {
        return m_overdubJniReadySteadyNs.load(std::memory_order_acquire);
    }

    bool stopRecording();

    struct OboeStreamSnapshot {
        int32_t sampleRateHz = 0;
        int32_t channelCount = 0;
        int32_t framesPerBurst = 0;
        int32_t bufferCapacityInFrames = 0;
        int32_t bufferSizeInFrames = 0;
        int32_t performanceMode = 0;
        int32_t sharingMode = 0;
        int32_t audioSessionId = 0;
    };

    OboeStreamSnapshot inputStreamSnapshot() const {
        return m_inputStreamSnapshot;
    }

    OboeStreamSnapshot outputStreamSnapshot() const {
        return m_outputStreamSnapshot;
    }

    void setOutputStreamForDiagnostics(std::shared_ptr<oboe::AudioStream> stream);

    std::shared_ptr<oboe::AudioStream> outputStreamForDiagnostics() const {
        return m_outputStreamForDiagnostics;
    }

    std::shared_ptr<oboe::AudioStream> inputStreamForDiagnostics() const {
        return m_inputStream;
    }

    int32_t inputReadBlockFrames() const {
        if (m_isRecording.load(std::memory_order_acquire) && m_sessionRecordReadFrames > 0) {
            return m_sessionRecordReadFrames;
        }
        return kInputReadBlockFrames;
    }

    struct PlaybackSessionTimings {
        int64_t playbackArmSteadyNs = 0;
        int64_t firstInputSampleSteadyNs = 0;
        int64_t firstNonSilentOutputSteadyNs = 0;
        int64_t firstAudibleOutputSteadyNs = 0;
        int32_t deferEnabled = 0;
        int32_t prerollFrames = 0;
        int32_t ioBatchFrames = 0;
        int32_t recordReadFrames = 0;
        int64_t playbackArmTransportStartFrame = 0;
        int64_t firstNonSilentTransportFrame = -1;
        int64_t firstAudiblePeakTransportFrame = -1;
        int64_t firstAudiblePeakMicro = 0;
        int64_t openInputBeginSteadyNs = 0;
        int64_t openInputDoneSteadyNs = 0;
        int64_t oboeStreamOpenBeginSteadyNs = 0;
        int64_t oboeStreamOpenDoneSteadyNs = 0;
        int64_t oboeStreamStartDoneSteadyNs = 0;
        int64_t firstOboeCallbackSteadyNs = 0;
    };

    struct SoftwareBufferProfile {
        int32_t ringDurationSeconds = 0;
        int32_t prerollWallMs = 0;
        int32_t ioBatchFrames = 0;
        int32_t inputReadFrames = 0;
        int32_t ioIdleSleepMs = 0;
        int32_t inputReadTimeoutMs = 0;
    };

    SoftwareBufferProfile softwareBufferProfile() const;

    struct CallbackCostSnapshot {
        int32_t callbackFrames = 0;
        int64_t sampleCount = 0;
        int64_t callbackMinUs = 0;
        int64_t callbackAvgUs = 0;
        int64_t callbackMaxUs = 0;
        int64_t callbackP95Us = 0;
        int64_t renderMinUs = 0;
        int64_t renderAvgUs = 0;
        int64_t renderMaxUs = 0;
        int64_t renderP95Us = 0;
        int32_t xRunCount = 0;
    };

    struct InputLoopCostSnapshot {
        int32_t readFrames = 0;
        int64_t sampleCount = 0;
        int64_t readBlockingMinUs = 0;
        int64_t readBlockingAvgUs = 0;
        int64_t readBlockingMaxUs = 0;
        int64_t readBlockingP95Us = 0;
        int64_t processingMinUs = 0;
        int64_t processingAvgUs = 0;
        int64_t processingMaxUs = 0;
        int64_t processingP95Us = 0;
    };

    void recordOutputCallbackCost(int32_t callbackFrames,
                                  int64_t callbackDurationUs,
                                  int64_t renderDurationUs);

    void recordInputLoopCost(int32_t readFrames,
                             int64_t readBlockingDurationUs,
                             int64_t processingDurationUs);

    CallbackCostSnapshot outputCallbackCostSnapshot() const;

    InputLoopCostSnapshot inputLoopCostSnapshot() const;

    PlaybackSessionTimings playbackSessionTimings() const;

    float recordingInputLevel() const {
        return m_recordingInputLevel.load(std::memory_order_acquire);
    }

    /**
     * Transport frame when the first input sample was captured.
     * [kRecordingFirstSampleTransportUnset] until the first non-empty read in [recordLoop].
     */
    int64_t recordingFirstSampleTransportFrame() const {
        return m_recordingFirstSampleTransportFrame.load(std::memory_order_acquire);
    }

    /** [recordingFirstSampleTransportFrame] as ms; [kRecordingFirstSampleTransportUnset] when unset. */
    int64_t recordingFirstSampleTransportPositionMs() const;

    /** PCM frames captured on the last recording session (mono/stereo frame count, not samples). */
    int64_t recordingCapturedFrameCount() const {
        return m_recordedCaptureFrameCount.load(std::memory_order_acquire);
    }

    int64_t recordingCapturedDurationMs() const;

    /** Session maximum pre-soft-clip master peak (linear) for the current playback arm. */
    float masterPeakHoldLinear() const {
        return m_masterPeakHoldLinear.load(std::memory_order_acquire);
    }

    /** Clears session peak-hold display; does not affect playback or transport. */
    void resetMasterPeakHold();

    bool setPlaybackSource(const std::string &wavPath,
                           float gain,
                           int64_t startPositionMs = 0,
                           int64_t sessionTimelineEndMs = 0);
    bool setPlaybackSources(const std::vector<std::string> &wavPaths,
                            const std::vector<float> &gains,
                            int64_t startPositionMs = 0,
                            int64_t sessionTimelineEndMs = 0,
                            const std::vector<int64_t> &laneClipStartMs = {},
                            const std::vector<int64_t> &laneClipDurationMs = {},
                            const std::vector<uint8_t> &laneLoopEnabled = {},
                            const std::vector<int64_t> &laneLoopSourceStartMs = {},
                            const std::vector<int64_t> &laneLoopSourceEndMs = {},
                            const std::vector<int64_t> &laneSourceTrimStartMs = {},
                            const std::vector<float> &lanePan = {},
                            bool deferPlaybackStart = false,
                            bool preserveActiveOverdubCapture = false);

    /**
     * Replace overdub backing lanes at [startPositionMs] while capture continues.
     * Same transport rules as live playback (no deferred gate); preserves first-sample capture state.
     */
    bool rearmOverdubPlaybackDuringRecording(
        const std::vector<std::string> &wavPaths,
        const std::vector<float> &gains,
        int64_t startPositionMs,
        int64_t sessionTimelineEndMs,
        const std::vector<int64_t> &laneClipStartMs,
        const std::vector<int64_t> &laneClipDurationMs,
        const std::vector<uint8_t> &laneLoopEnabled,
        const std::vector<int64_t> &laneLoopSourceStartMs,
        const std::vector<int64_t> &laneLoopSourceEndMs,
        const std::vector<int64_t> &laneSourceTrimStartMs,
        const std::vector<float> &lanePan);

    /** Live per-lane gain (0..1); safe while Oboe is running — atomic read in [render]. */
    void setPlaybackLaneGain(std::size_t laneIndex, float gain);

    /** Live per-lane pan (-1..+1); safe while Oboe is running — atomic read in [render]. */
    void setPlaybackLanePan(std::size_t laneIndex, float pan);

    /** HJ.1 live audibility; lane must be [PlaybackLaneLifecycle::Active]. */
    void setPlaybackLaneAudible(std::size_t laneIndex, bool audible);

    /**
     * HJ.2: reserve a fixed slot and enqueue async prepare+commit. Returns lane index (0..7) or -1.
     * Safe while Oboe is running; does not rebuild the session.
     */
    int32_t beginHotJoinLane(const std::string &wavPath,
                             float gain,
                             int64_t clipStartMs = 0,
                             int64_t clipDurationMs = 0,
                             bool loopEnabled = false,
                             int64_t loopSourceStartMs = 0,
                             int64_t loopSourceEndMs = 0,
                             int64_t sourceTrimStartMs = 0,
                             float pan = 0.0f);

    /** HJ.2: cancel a preparing/ready lane; no-op for active session lanes (use [setPlaybackLaneAudible]). */
    void cancelHotJoinLane(std::size_t laneIndex);

    PlaybackLaneLifecycle laneLifecycle(std::size_t laneIndex) const;

    bool isPlaybackActive() const { return m_isPlaying.load(std::memory_order_acquire); }
    void stopPlayback();

    /**
     * Live transport (Clock.4): [TransportClockAnchor] + CLOCK_MONOTONIC is the sole live clock when
     * installed. [m_masterPlaybackFrame] is a render-path cache / offline fallback only — never a
     * second advancing clock during live sessions.
     */
    int64_t transportStartFrame() const;

    int64_t transportFrame() const;

    int64_t transportPositionMs() const;

    int32_t playbackChannelCount() const {
        return m_playbackLanes[0].srcChannels.load(std::memory_order_acquire);
    }

    void releasePlaybackResources();

    void render(float *outputInterleaved, int32_t numFrames, int32_t channels, int32_t sampleRate);

    /** Logs [PLAYBACK_STARTUP_BREAKDOWN] relative to the latest playback_arm anchor. */
    void logPlaybackStartupMilestone(const char *stage);

    /** Returns true the first time an Oboe output callback runs after playback_arm. */
    bool logFirstOboeCallbackOnce();

    enum class OfflineMixdownStatus : int {
        Success = 0,
        Failed = 1,
        Cancelled = 2,
    };

    /** Requests in-flight [renderOfflineMixdown] to stop and discard partial output. */
    void requestOfflineMixdownCancel();

    /**
     * Offline bounce using the same [renderBlock] mixer path as live Oboe playback.
     * Caller must not invoke while live playback is active on the same engine instance.
     */
    OfflineMixdownStatus renderOfflineMixdown(
        int32_t sampleRate,
        const std::vector<std::string> &wavPaths,
        const std::vector<float> &gains,
        int64_t startPositionMs,
        int64_t sessionTimelineEndMs,
        const std::vector<int64_t> &laneClipStartMs,
        const std::vector<int64_t> &laneClipDurationMs,
        const std::vector<uint8_t> &laneLoopEnabled,
        const std::vector<int64_t> &laneLoopSourceStartMs,
        const std::vector<int64_t> &laneLoopSourceEndMs,
        const std::vector<int64_t> &laneSourceTrimStartMs,
        const std::vector<float> &lanePan,
        const std::string &outputPath,
        const std::function<void(float)> &progressCallback);

private:
    enum class RenderBlockInputMode {
        RingBuffer,
        DirectSource,
    };

    /**
     * Shared stereo mix block used by live [render] and offline bounce.
     * @return true when at least one lane contributed samples.
     */
    bool renderBlock(float *outputInterleaved,
                     int32_t numFrames,
                     int32_t outChannels,
                     int64_t transportFrameAtBlock,
                     RenderBlockInputMode inputMode,
                     bool applyMasterSoftClip,
                     bool playbackMutexAlreadyHeld = false,
                     int32_t *outMinFramesReturned = nullptr);

    void syncLaneSourcesForOfflineTransport(int64_t transportFrameAtBlock);

private:
    static constexpr std::size_t kPlaybackLaneCount = 16;
    static constexpr std::size_t kPlaybackLaneProductCap = 8;

    struct PlaybackLaneSlot {
        std::shared_ptr<IAudioSource> source;
        std::shared_ptr<RingBuffer> ring;
        std::string currentPath;

        std::atomic<PlaybackLaneLifecycle> lifecycle{PlaybackLaneLifecycle::Inactive};
        std::atomic<bool> sourceExhausted{false};
        std::atomic<bool> audibleEnabled{true};
        /** Cleared by [cancelHotJoinLane]; commit publishes audible only if still true (HJ.2). */
        std::atomic<bool> hotJoinPublishAudible{true};
        std::atomic<float> gain{1.0f};
        std::atomic<float> pan{0.0f};
        std::atomic<int32_t> srcChannels{0};
        std::atomic<int64_t> clipTimelineStartFrame{0};
        /** 0 = no explicit timeline clip end (lane active until WAV exhausted). */
        std::atomic<int64_t> clipTimelineEndFrame{0};
        std::atomic<bool> loopEnabled{false};
        std::atomic<int64_t> sourceLoopStartFrame{0};
        /** Exclusive end frame inside the WAV; 0 when loop disabled. */
        std::atomic<int64_t> sourceLoopEndFrame{0};
        /** Non-destructive source read offset (frames from WAV start). */
        std::atomic<int64_t> sourceTrimStartFrame{0};
    };

    struct HotJoinStagingSlot {
        std::shared_ptr<IAudioSource> source;
        std::shared_ptr<RingBuffer> ring;
        std::string path;
        float gain = 1.0f;
        float pan = 0.0f;
        int32_t channels = 0;
        int64_t clipStartMs = 0;
        int64_t clipDurationMs = 0;
        bool loopEnabled = false;
        int64_t loopSourceStartMs = 0;
        int64_t loopSourceEndMs = 0;
        int64_t sourceTrimStartMs = 0;
    };

    struct HotJoinWorkItem {
        std::size_t laneIndex = 0;
        std::string wavPath;
        float gain = 1.0f;
        float pan = 0.0f;
        int64_t clipStartMs = 0;
        int64_t clipDurationMs = 0;
        bool loopEnabled = false;
        int64_t loopSourceStartMs = 0;
        int64_t loopSourceEndMs = 0;
        int64_t sourceTrimStartMs = 0;
    };

    static int32_t computeRingFramesForSampleRate(int32_t sampleRateHz);
    static int32_t computePrerollFramesForSampleRate(int32_t sampleRateHz);

    static constexpr int32_t kInputReadBlockFrames = 256;

    void clearPlaybackLanesLocked();
    void deactivateAuxiliaryLanesLocked();
    void setLaneInactiveLocked(PlaybackLaneSlot &lane);

    bool armOnePlaybackLaneLocked(std::size_t laneIndex,
                                  const std::string &wavPath,
                                  float laneGain,
                                  int64_t transportStartFrame,
                                  int64_t clipStartMs,
                                  int64_t clipDurationMs,
                                  bool reuseExistingSourceOnSamePath,
                                  bool loopEnabled = false,
                                  int64_t loopSourceStartMs = 0,
                                  int64_t loopSourceEndMs = 0,
                                  int64_t sourceTrimStartMs = 0,
                                  float lanePan = 0.0f);
    bool armPlaybackLanesLocked(const std::vector<std::string> &wavPaths,
                                const std::vector<float> &gains,
                                int64_t transportStartFrame,
                                const std::vector<int64_t> &laneClipStartMs,
                                const std::vector<int64_t> &laneClipDurationMs,
                                const std::vector<uint8_t> &laneLoopEnabled,
                                const std::vector<int64_t> &laneLoopSourceStartMs,
                                const std::vector<int64_t> &laneLoopSourceEndMs,
                                const std::vector<int64_t> &laneSourceTrimStartMs = {},
                                const std::vector<float> &lanePan = {});

    bool openInputStream(int32_t channelCount);
    void closeInputStream();
    void configureInputReadSizeForSession();
    void recordLoop();
    void onRecordingFramesCaptured(int32_t framesRead, int64_t appReceiveMonotonicNs);
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
    void installTransportClockAnchor(int64_t transportStartFrame,
                                     int64_t monotonicStartNs,
                                     const char *reason);
    void resetTransportClockAnchor();

    int64_t transportFrameAtMonotonicNs(int64_t monotonicNs) const;

    int64_t estimatedCaptureMonotonicNs(int64_t appReceiveMonotonicNs) const;

    int64_t estimatedCaptureTransportFrame(int64_t appReceiveMonotonicNs) const;

    int64_t currentTransportFrame() const;

    void captureInputStreamSnapshot();

    static int64_t steadyClockNowNs();

    void capturePlaybackOutputMilestones(int64_t transportFrameAtBlock,
                                         const float *outputInterleaved,
                                         int32_t numFrames,
                                         int32_t channels);

    void resetPlaybackStartupBreakdownFlags();

    void captureStartupSteadyNsForStage(const char *stage, int64_t steadyNs);

    PlaybackLaneLifecycle loadLaneLifecycle(std::size_t laneIndex) const;

    void markPlaybackLaneExhaustedLocked(std::size_t laneIndex);

    int32_t m_sampleRate = 44'100;
    int32_t m_fileBitDepth = 16;

    std::mutex m_recordMutex;
    std::vector<float> m_recordedSamples;
    std::string m_recordingOutputPath;
    int32_t m_recordingChannelCount = 1;
    int32_t m_sessionRecordReadFrames = kInputReadBlockFrames;
    std::string m_sessionInputRouteKey;
    std::shared_ptr<oboe::AudioStream> m_inputStream;
    std::shared_ptr<oboe::AudioStream> m_outputStreamForDiagnostics;
    std::thread m_recordThread;
    std::atomic<bool> m_isRecording{false};
    std::atomic<int64_t> m_recordingFirstSampleTransportFrame{kRecordingFirstSampleTransportUnset};
    std::atomic<int64_t> m_recordedCaptureFrameCount{0};
    std::atomic<bool> m_awaitingDeferredPlaybackStart{false};
    std::atomic<int64_t> m_deferredPlaybackStartFrame{0};
    std::atomic<float> m_recordingInputLevel{0.0f};
    std::atomic<float> m_masterPeakHoldLinear{0.0f};

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
    /** Set at arm when any lane has loop enabled; prevents lane-drain auto-stop. */
    alignas(8) std::atomic<bool> m_sessionHasLoopLanes{false};

    std::thread m_ioThread;
    std::atomic<bool> m_ioRunning{false};
    std::atomic<bool> m_offlineMixdownCancelRequested{false};

    OboeStreamSnapshot m_inputStreamSnapshot{};
    OboeStreamSnapshot m_outputStreamSnapshot{};

    std::atomic<int64_t> m_overdubPlaybackArmSteadyNs{0};
    std::atomic<int64_t> m_overdubFirstInputSteadyNs{0};
    std::atomic<int64_t> m_overdubFirstNonSilentOutputSteadyNs{0};
    std::atomic<int64_t> m_overdubFirstAudibleOutputSteadyNs{0};
    std::atomic<int32_t> m_overdubDeferEnabledAtArm{0};
    std::atomic<int64_t> m_playbackArmTransportStartFrame{0};
    std::atomic<int64_t> m_firstNonSilentTransportFrame{-1};
    std::atomic<int64_t> m_firstAudiblePeakTransportFrame{-1};
    std::atomic<int64_t> m_firstAudiblePeakMicro{0};

    std::atomic<int64_t> m_openInputBeginSteadyNs{0};
    std::atomic<int64_t> m_openInputDoneSteadyNs{0};
    std::atomic<int64_t> m_oboeStreamOpenBeginSteadyNs{0};
    std::atomic<int64_t> m_oboeStreamOpenDoneSteadyNs{0};
    std::atomic<int64_t> m_oboeStreamStartDoneSteadyNs{0};
    std::atomic<int64_t> m_firstOboeCallbackSteadyNs{0};
    std::atomic<int64_t> m_overdubJniReadySteadyNs{0};

    alignas(8) std::atomic<int64_t> m_anchorTransportStartFrame{0};
    alignas(8) std::atomic<int64_t> m_anchorMonotonicStartNs{0};
    alignas(8) std::atomic<int32_t> m_anchorSampleRateHz{0};
    alignas(8) std::atomic<bool> m_transportClockAnchorValid{false};
    alignas(8) std::atomic<int64_t> m_sessionInputLatencyNs{0};
    alignas(8) std::atomic<int64_t> m_sessionOutputLatencyNs{0};
    alignas(8) std::atomic<int64_t> m_liveOutputLatencyNs{0};
    alignas(8) std::atomic<bool> m_liveOutputLatencyValid{false};
    alignas(8) std::atomic<bool> m_outputRenderAheadLogged{false};
    alignas(8) std::atomic<int64_t> m_inputCaptureBufferIndex{0};

    std::atomic<int64_t> m_startupLastMilestoneNs{0};
    std::atomic<bool> m_startupIoPrefetchLogged{false};
    std::atomic<bool> m_startupFirstRenderLogged{false};
    std::atomic<bool> m_startupFirstOboeCallbackLogged{false};

    std::vector<float> m_renderScratch;

    DurationUsStats m_outputCallbackDurationUs;
    DurationUsStats m_outputRenderDurationUs;
    std::atomic<int32_t> m_lastOutputCallbackFrames{0};

    DurationUsStats m_inputReadBlockingDurationUs;
    DurationUsStats m_inputProcessingDurationUs;
    std::atomic<int32_t> m_lastInputReadFrames{0};
};

} // namespace dawengine
