#include "AudioEngine.h"

#include <cassert>
#include <memory>
#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <cmath>
#include <utility>

#include "AudioSource.h"
#include "LocalWavSource.h"

namespace {

constexpr int64_t kReadTimeoutNanos = 100 * oboe::kNanosPerMillisecond;
constexpr int32_t kFramesPerRead = 256;
constexpr uint16_t kWavBitsPerSample = 16;

void WriteUint16LE(FILE *file, uint16_t value) {
    const std::array<uint8_t, 2> bytes = {
        static_cast<uint8_t>(value & 0xFFu),
        static_cast<uint8_t>((value >> 8u) & 0xFFu)
    };
    std::fwrite(bytes.data(), 1, bytes.size(), file);
}

void WriteUint32LE(FILE *file, uint32_t value) {
    const std::array<uint8_t, 4> bytes = {
        static_cast<uint8_t>(value & 0xFFu),
        static_cast<uint8_t>((value >> 8u) & 0xFFu),
        static_cast<uint8_t>((value >> 16u) & 0xFFu),
        static_cast<uint8_t>((value >> 24u) & 0xFFu)
    };
    std::fwrite(bytes.data(), 1, bytes.size(), file);
}

int16_t FloatToPcm16(float sample) {
    const float clamped = std::max(-1.0f, std::min(1.0f, sample));
    return static_cast<int16_t>(clamped * 32767.0f);
}

int64_t playbackStartFrameFromMs(int64_t startPositionMs, int32_t sampleRateHz) {
    if (startPositionMs <= 0 || sampleRateHz <= 0) return 0;
    return (static_cast<int64_t>(sampleRateHz) * startPositionMs) / 1000;
}

bool seekSourceToStartFrame(dawengine::IAudioSource &source, int64_t startFrame) {
    const int64_t total = source.totalFrames();
    int64_t frame = startFrame < 0 ? 0 : startFrame;
    if (frame > total) frame = total;
    return source.seekToFrame(frame);
}

bool isSourceExhaustedAtStart(const dawengine::IAudioSource &source, int64_t startFrame) {
    return startFrame >= source.totalFrames();
}

int64_t clipTimelineEndFrameForLane(int64_t clipStartFrame,
                                    int64_t clipDurationMs,
                                    int32_t sampleRateHz) {
    if (clipDurationMs <= 0 || sampleRateHz <= 0) return 0;
    return clipStartFrame + playbackStartFrameFromMs(clipDurationMs, sampleRateHz);
}

bool laneActiveOnTimeline(int64_t transportFrame,
                          int64_t clipStartFrame,
                          int64_t clipEndFrame) {
    if (transportFrame < clipStartFrame) return false;
    if (clipEndFrame > 0 && transportFrame >= clipEndFrame) return false;
    return true;
}

int64_t laneSourceSeekFrame(int64_t transportStartFrame, int64_t clipStartFrame) {
    if (transportStartFrame <= clipStartFrame) return 0;
    return transportStartFrame - clipStartFrame;
}

} // namespace

namespace dawengine {

namespace {

void storeLaneRing(std::shared_ptr<RingBuffer> &slot, std::shared_ptr<RingBuffer> value) {
    std::atomic_store_explicit(&slot, std::move(value), std::memory_order_release);
}

std::shared_ptr<RingBuffer> loadLaneRing(const std::shared_ptr<RingBuffer> &slot) {
    return std::atomic_load_explicit(&slot, std::memory_order_acquire);
}

} // namespace

namespace playback {
constexpr int32_t kRingDurationSeconds = 1;
// Wall-time preroll: ms × SR / 1000 (computePrerollFramesForSampleRate).
constexpr int32_t kPrerollWallMs = 30;

// Larger I/O batches — producer chunk size in frames only (not tied to SR).
constexpr int32_t kIoBatchFrames = 1'024;
constexpr int kIoIdleSleepMs = 4;

constexpr int32_t kMaxRenderFramesPerCallback = 4'096;
constexpr std::size_t kRenderScratchFloatCount =
    static_cast<std::size_t>(kMaxRenderFramesPerCallback) * 2u;

// Transparent master safety stage: exact pass-through below the threshold,
// then a smooth asymptotic knee that prevents summed playback from hard clipping.
constexpr float kMasterSafetyThreshold = 0.99f;
constexpr float kMasterSafetyHeadroom = 1.0f - kMasterSafetyThreshold;

float ApplyMasterSafetySoftClip(float sample) {
    const float magnitude = sample < 0.0f ? -sample : sample;
    if (magnitude <= kMasterSafetyThreshold) {
        return sample;
    }

    const float over = magnitude - kMasterSafetyThreshold;
    const float shaped = kMasterSafetyThreshold +
                         kMasterSafetyHeadroom * (over / (over + kMasterSafetyHeadroom));
    return sample < 0.0f ? -shaped : shaped;
}

void ProcessMasterSafetySoftClip(float *interleaved, std::size_t sampleCount) {
    for (std::size_t i = 0; i < sampleCount; ++i) {
        interleaved[i] = ApplyMasterSafetySoftClip(interleaved[i]);
    }
}

} // namespace playback

AudioEngine::AudioEngine() = default;

int32_t AudioEngine::computeRingFramesForSampleRate(int32_t sampleRateHz) {
    return static_cast<int32_t>(static_cast<int64_t>(sampleRateHz) *
                                static_cast<int64_t>(playback::kRingDurationSeconds));
}

int32_t AudioEngine::computePrerollFramesForSampleRate(int32_t sampleRateHz) {
    return static_cast<int32_t>((static_cast<int64_t>(sampleRateHz) *
                                 static_cast<int64_t>(playback::kPrerollWallMs)) /
                                1000);
}

PlaybackLaneLifecycle AudioEngine::loadLaneLifecycle(const std::size_t laneIndex) const {
    if (laneIndex >= kPlaybackLaneCount) {
        return PlaybackLaneLifecycle::Inactive;
    }
    return m_playbackLanes[laneIndex].lifecycle.load(std::memory_order_acquire);
}

void AudioEngine::markPlaybackLaneExhaustedLocked(const std::size_t laneIndex) {
    if (laneIndex >= kPlaybackLaneCount) {
        return;
    }
    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
    const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIndex);
    if (!laneLifecycleParticipatesInMix(state)) {
        return;
    }
    PlaybackLaneSlot &lane = m_playbackLanes[laneIndex];
    lane.sourceExhausted.store(true, std::memory_order_release);
    lane.lifecycle.store(PlaybackLaneLifecycle::Exhausted, std::memory_order_release);
}

void AudioEngine::setLaneInactiveLocked(PlaybackLaneSlot &lane) {
    storeLaneRing(lane.ring, {});
    lane.source.reset();
    lane.currentPath.clear();
    lane.sourceExhausted.store(false, std::memory_order_release);
    lane.audibleEnabled.store(true, std::memory_order_release);
    lane.hotJoinPublishAudible.store(true, std::memory_order_release);
    lane.gain.store(1.0f, std::memory_order_release);
    lane.srcChannels.store(0, std::memory_order_release);
    lane.clipTimelineStartFrame.store(0, std::memory_order_release);
    lane.clipTimelineEndFrame.store(0, std::memory_order_release);
    lane.lifecycle.store(PlaybackLaneLifecycle::Inactive, std::memory_order_release);
}

void AudioEngine::clearPlaybackLanesLocked() {
    for (PlaybackLaneSlot &lane : m_playbackLanes) {
        setLaneInactiveLocked(lane);
    }
    for (std::size_t i = 0; i < kPlaybackLaneProductCap; ++i) {
        clearHotJoinStagingLocked(i);
    }
}

void AudioEngine::deactivateAuxiliaryLanesLocked() {
    for (std::size_t i = 1; i < kPlaybackLaneCount; ++i) {
        setLaneInactiveLocked(m_playbackLanes[i]);
    }
}

bool AudioEngine::armOnePlaybackLaneLocked(const std::size_t laneIndex,
                                           const std::string &wavPath,
                                           const float laneGain,
                                           const int64_t transportStartFrame,
                                           const int64_t clipStartMs,
                                           const int64_t clipDurationMs,
                                           const bool reuseExistingSourceOnSamePath) {
    if (laneIndex >= kPlaybackLaneProductCap || wavPath.empty()) {
        return false;
    }

    const int64_t clipStartFrame = playbackStartFrameFromMs(clipStartMs, m_sampleRate);
    const int64_t clipEndFrame =
        clipTimelineEndFrameForLane(clipStartFrame, clipDurationMs, m_sampleRate);
    const int64_t sourceSeekFrame =
        laneSourceSeekFrame(transportStartFrame, clipStartFrame);
    const bool activeOnTimeline =
        laneActiveOnTimeline(transportStartFrame, clipStartFrame, clipEndFrame);
    const bool beforeClipStart = transportStartFrame < clipStartFrame;
    const bool pastClipEnd =
        clipEndFrame > 0 && transportStartFrame >= clipEndFrame;

    PlaybackLaneSlot &lane = m_playbackLanes[laneIndex];
    lane.gain.store(laneGain, std::memory_order_release);
    lane.audibleEnabled.store(true, std::memory_order_release);
    lane.clipTimelineStartFrame.store(clipStartFrame, std::memory_order_release);
    lane.clipTimelineEndFrame.store(clipEndFrame, std::memory_order_release);

    const bool samePath =
        reuseExistingSourceOnSamePath && !!lane.source && wavPath == lane.currentPath &&
        !lane.source->hasDiskContentChanged();
    const int32_t ringFrameCapacity = computeRingFramesForSampleRate(m_sampleRate);
    const int32_t prerollTargetFrames = computePrerollFramesForSampleRate(m_sampleRate);
    bool exhaustedAtStart = false;

    if (samePath) {
        if (!seekSourceToStartFrame(*lane.source, sourceSeekFrame)) return false;
        if (pastClipEnd) {
            exhaustedAtStart = true;
        } else if (beforeClipStart) {
            exhaustedAtStart = false;
        } else {
            exhaustedAtStart = isSourceExhaustedAtStart(*lane.source, sourceSeekFrame);
        }
        if (std::shared_ptr<RingBuffer> ring = loadLaneRing(lane.ring)) {
            ring->reset();
        }
    } else {
        auto source = std::make_shared<LocalWavSource>(wavPath);
        if (!source->open()) return false;
        if (source->sampleRate() != m_sampleRate) return false;
        if (source->channelCount() < 1 || source->channelCount() > 2) return false;

        const std::size_t ringFloats = static_cast<std::size_t>(ringFrameCapacity) *
                                       static_cast<std::size_t>(source->channelCount());
        storeLaneRing(lane.ring, std::make_shared<RingBuffer>(ringFloats));
        lane.srcChannels.store(source->channelCount(), std::memory_order_release);
        lane.source = std::move(source);
        lane.currentPath = wavPath;
        if (!seekSourceToStartFrame(*lane.source, sourceSeekFrame)) {
            return false;
        }
        if (pastClipEnd) {
            exhaustedAtStart = true;
        } else if (beforeClipStart) {
            exhaustedAtStart = false;
        } else {
            exhaustedAtStart = isSourceExhaustedAtStart(*lane.source, sourceSeekFrame);
        }
    }

    lane.sourceExhausted.store(exhaustedAtStart, std::memory_order_release);
    lane.lifecycle.store(PlaybackLaneLifecycle::Active, std::memory_order_release);

    if (!exhaustedAtStart && activeOnTimeline) {
        const int32_t channels = lane.source->channelCount();
        std::vector<float> preroll(static_cast<std::size_t>(prerollTargetFrames) *
                                   static_cast<std::size_t>(channels));
        const int32_t prerollFrames = lane.source->readFrames(preroll.data(), prerollTargetFrames);
        if (prerollFrames < 0) {
            return false;
        }
        if (prerollFrames > 0) {
            if (std::shared_ptr<RingBuffer> ring = loadLaneRing(lane.ring)) {
                ring->write(
                preroll.data(),
                static_cast<std::size_t>(prerollFrames) * static_cast<std::size_t>(channels));
            }
        }
    }

    return true;
}

bool AudioEngine::armPlaybackLanesLocked(const std::vector<std::string> &wavPaths,
                                         const std::vector<float> &gains,
                                         const int64_t transportStartFrame,
                                         const std::vector<int64_t> &laneClipStartMs,
                                         const std::vector<int64_t> &laneClipDurationMs) {
    const std::size_t laneCount = wavPaths.size();
    if (laneCount == 0 ||
        laneCount > kPlaybackLaneProductCap ||
        laneCount != gains.size()) {
        clearPlaybackLanesLocked();
        return false;
    }

    if (laneCount == 1) {
        deactivateAuxiliaryLanesLocked();
        const int64_t clipStartMs =
            laneClipStartMs.empty() ? 0 : laneClipStartMs[0];
        const int64_t clipDurationMs =
            laneClipDurationMs.empty() ? 0 : laneClipDurationMs[0];
        if (!armOnePlaybackLaneLocked(
                0,
                wavPaths[0],
                gains[0],
                transportStartFrame,
                clipStartMs,
                clipDurationMs,
                true)) {
            clearPlaybackLanesLocked();
            return false;
        }
        m_renderScratch.resize(playback::kRenderScratchFloatCount);
        return true;
    }

    clearPlaybackLanesLocked();
    m_renderScratch.resize(playback::kRenderScratchFloatCount);

    for (std::size_t laneIdx = 0; laneIdx < laneCount; ++laneIdx) {
        const int64_t clipStartMs =
            laneIdx < laneClipStartMs.size() ? laneClipStartMs[laneIdx] : 0;
        const int64_t clipDurationMs =
            laneIdx < laneClipDurationMs.size() ? laneClipDurationMs[laneIdx] : 0;
        if (!armOnePlaybackLaneLocked(
                laneIdx,
                wavPaths[laneIdx],
                gains[laneIdx],
                transportStartFrame,
                clipStartMs,
                clipDurationMs,
                false)) {
            clearPlaybackLanesLocked();
            return false;
        }
    }

    return true;
}

AudioEngine::~AudioEngine() noexcept {
    try {
        releasePlaybackResources();
        stopRecording();
    } catch (...) {
        // join()/teardown must not escape the destructor.
    }
}

void AudioEngine::configureProject(int32_t sampleRate, int32_t fileBitDepth) {
    m_sampleRate = sampleRate;
    m_fileBitDepth = fileBitDepth;
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------

bool AudioEngine::openInputStream(int32_t channelCount) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setSampleRate(m_sampleRate);
    builder.setChannelCount(channelCount);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result openResult = builder.openStream(stream);
    if (openResult != oboe::Result::OK || !stream) return false;
    if (stream->requestStart() != oboe::Result::OK) {
        stream->close();
        return false;
    }
    m_inputStream = stream;
    return true;
}

void AudioEngine::closeInputStream() {
    if (!m_inputStream) return;
    m_inputStream->close();
    m_inputStream.reset();
}

bool AudioEngine::startRecording(int32_t channelCount,
                                 const std::string &outputPath,
                                 const int64_t startPositionMs) {
    if (outputPath.empty() || m_isRecording.exchange(true)) {
        return false;
    }

    {
        std::lock_guard<std::mutex> recordLock(m_recordMutex);
        m_recordedSamples.clear();
        m_recordingOutputPath = outputPath;
        m_recordingChannelCount = channelCount == 2 ? 2 : 1;
    }
    m_recordingInputLevel.store(0.0f, std::memory_order_release);

    if (!openInputStream(m_recordingChannelCount)) {
        m_isRecording = false;
        m_recordingInputLevel.store(0.0f, std::memory_order_release);
        return false;
    }

    // Clock.3: seed transport from timeline when recording-only; play+record keeps playback timeline.
    const bool playbackAlreadyActive = m_isPlaying.load(std::memory_order_acquire);
    if (!playbackAlreadyActive) {
        const int64_t startFrame = playbackStartFrameFromMs(startPositionMs, m_sampleRate);
        initializeMasterPlaybackTimeline(startFrame);
    }

    m_recordThread = std::thread(&AudioEngine::recordLoop, this);
    return true;
}

void AudioEngine::recordLoop() {
    const int32_t channelCount = std::max(1, m_recordingChannelCount);
    std::vector<float> buffer(static_cast<size_t>(kFramesPerRead * channelCount));

    while (m_isRecording) {
        if (!m_inputStream) break;
        const auto result = m_inputStream->read(buffer.data(), kFramesPerRead, kReadTimeoutNanos);
        if (!result) {
            if (result.error() != oboe::Result::ErrorTimeout) {
                break;
            }
            continue;
        }

        const int32_t framesRead = result.value();
        if (framesRead <= 0) continue;

        // Clock.3: single writer — record loop advances transport only when playback is inactive.
        if (!m_isPlaying.load(std::memory_order_acquire)) {
            m_masterPlaybackFrame.fetch_add(static_cast<int64_t>(framesRead),
                                            std::memory_order_release);
        }

        const size_t sampleCount =
            static_cast<size_t>(framesRead) * static_cast<size_t>(channelCount);
        float peak = 0.0f;
        for (size_t i = 0; i < sampleCount; ++i) {
            peak = std::max(peak, std::fabs(buffer[i]));
        }
        m_recordingInputLevel.store(std::min(peak, 1.0f), std::memory_order_release);

        std::lock_guard<std::mutex> recordLock(m_recordMutex);
        m_recordedSamples.insert(
            m_recordedSamples.end(),
            buffer.begin(),
            buffer.begin() + static_cast<std::ptrdiff_t>(sampleCount)
        );
    }
}

bool AudioEngine::writeRecordingToWav(const std::vector<float> &samples,
                                      int32_t channelCount,
                                      const std::string &outputPath) const {
    if (outputPath.empty()) return false;

    FILE *file = std::fopen(outputPath.c_str(), "wb");
    if (!file) return false;

    const uint32_t bytesPerSample = kWavBitsPerSample / 8u;
    const uint32_t dataSize = static_cast<uint32_t>(samples.size() * bytesPerSample);
    const uint16_t wavChannelCount = static_cast<uint16_t>(std::max(1, channelCount));
    const uint32_t byteRate = static_cast<uint32_t>(m_sampleRate) * wavChannelCount * bytesPerSample;
    const uint16_t blockAlign = static_cast<uint16_t>(wavChannelCount * bytesPerSample);

    std::fwrite("RIFF", 1, 4, file);
    WriteUint32LE(file, 36u + dataSize);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    WriteUint32LE(file, 16u);
    WriteUint16LE(file, 1u);
    WriteUint16LE(file, wavChannelCount);
    WriteUint32LE(file, static_cast<uint32_t>(m_sampleRate));
    WriteUint32LE(file, byteRate);
    WriteUint16LE(file, blockAlign);
    WriteUint16LE(file, kWavBitsPerSample);
    std::fwrite("data", 1, 4, file);
    WriteUint32LE(file, dataSize);

    for (float sample : samples) {
        const int16_t pcm16 = FloatToPcm16(sample);
        std::fwrite(&pcm16, sizeof(pcm16), 1, file);
    }

    const bool writeOk = std::ferror(file) == 0;
    std::fclose(file);
    return writeOk;
}

bool AudioEngine::stopRecording() {
    if (!m_isRecording.exchange(false)) {
        return false;
    }

    if (m_inputStream) {
        m_inputStream->requestStop();
    }
    if (m_recordThread.joinable()) {
        m_recordThread.join();
    }
    closeInputStream();
    m_recordingInputLevel.store(0.0f, std::memory_order_release);

    std::vector<float> recordedSamples;
    std::string outputPath;
    int32_t channelCount = 1;
    {
        std::lock_guard<std::mutex> recordLock(m_recordMutex);
        recordedSamples = m_recordedSamples;
        outputPath = m_recordingOutputPath;
        channelCount = m_recordingChannelCount;
        m_recordedSamples.clear();
        m_recordingOutputPath.clear();
    }

    if (!m_isPlaying.load(std::memory_order_acquire)) {
        resetMasterPlaybackTimeline();
    }

    return writeRecordingToWav(recordedSamples, channelCount, outputPath);
}

// ---------------------------------------------------------------------------
// Playback (streaming)
// ---------------------------------------------------------------------------

bool AudioEngine::setPlaybackSource(const std::string &wavPath,
                                    float gain,
                                    int64_t startPositionMs,
                                    int64_t sessionTimelineEndMs) {
    return setPlaybackSources(
        std::vector<std::string>{wavPath},
        std::vector<float>{gain},
        startPositionMs,
        sessionTimelineEndMs);
}

int64_t AudioEngine::transportPositionMs() const {
    const int64_t frame = m_masterPlaybackFrame.load(std::memory_order_acquire);
    const int32_t rate = m_sampleRate;
    if (rate <= 0) return 0L;
    return (frame * 1000L) / static_cast<int64_t>(rate);
}

void AudioEngine::initializeMasterPlaybackTimeline(const int64_t startFrame) {
    const int64_t frame = startFrame < 0 ? 0 : startFrame;
    m_masterPlaybackStartFrame.store(frame, std::memory_order_release);
    m_masterPlaybackFrame.store(frame, std::memory_order_release);
}

void AudioEngine::resetMasterPlaybackTimeline() {
    m_masterPlaybackStartFrame.store(0, std::memory_order_release);
    m_masterPlaybackFrame.store(0, std::memory_order_release);
    m_playbackSessionEndFrame.store(0, std::memory_order_release);
}

bool AudioEngine::setPlaybackSources(const std::vector<std::string> &wavPaths,
                                     const std::vector<float> &gains,
                                     int64_t startPositionMs,
                                     int64_t sessionTimelineEndMs,
                                     const std::vector<int64_t> &laneClipStartMs,
                                     const std::vector<int64_t> &laneClipDurationMs) {
    const int64_t startFrame = playbackStartFrameFromMs(startPositionMs, m_sampleRate);
    int64_t endFrame = playbackStartFrameFromMs(sessionTimelineEndMs, m_sampleRate);
    if (endFrame > 0 && endFrame < startFrame) {
        endFrame = startFrame;
    }
    m_playbackSessionEndFrame.store(endFrame, std::memory_order_release);
    m_isPlaying.store(false, std::memory_order_release);
    stopIoThread();

    {
        std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
        for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneProductCap; ++laneIdx) {
            const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
            if (state == PlaybackLaneLifecycle::Preparing ||
                state == PlaybackLaneLifecycle::ReadyToCommit) {
                clearHotJoinStagingLocked(laneIdx);
                setLaneInactiveLocked(m_playbackLanes[laneIdx]);
            }
        }
        if (!armPlaybackLanesLocked(
                wavPaths, gains, startFrame, laneClipStartMs, laneClipDurationMs)) {
            resetMasterPlaybackTimeline();
            return false;
        }
    }

    initializeMasterPlaybackTimeline(startFrame);
    ensureIoThreadRunning();
    ensureHotJoinThreadRunning();
    m_isPlaying.store(true, std::memory_order_release);
    return true;
}

void AudioEngine::setPlaybackGain(float gain) {
    m_playbackLanes[0].gain.store(gain, std::memory_order_release);
}

void AudioEngine::setPlaybackLaneAudible(const std::size_t laneIndex, const bool audible) {
    if (laneIndex >= kPlaybackLaneProductCap) {
        return;
    }
    if (loadLaneLifecycle(laneIndex) != PlaybackLaneLifecycle::Active) {
        return;
    }
    if (m_playbackLanes[laneIndex].srcChannels.load(std::memory_order_acquire) <= 0) {
        return;
    }
    m_playbackLanes[laneIndex].audibleEnabled.store(audible, std::memory_order_release);
}

void AudioEngine::stopPlayback() {
    m_isPlaying.store(false, std::memory_order_release);
    stopIoThread();
    // Clock.3: overdub backing end — recording continues; preserve transport for recordLoop.
    if (!m_isRecording.load(std::memory_order_acquire)) {
        resetMasterPlaybackTimeline();
    }

    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);

    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneProductCap; ++laneIdx) {
        const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
        if (state == PlaybackLaneLifecycle::Preparing ||
            state == PlaybackLaneLifecycle::ReadyToCommit) {
            clearHotJoinStagingLocked(laneIdx);
        }
    }

    for (PlaybackLaneSlot &lane : m_playbackLanes) {
        lane.sourceExhausted.store(false, std::memory_order_release);
        if (std::shared_ptr<RingBuffer> ring = loadLaneRing(lane.ring)) {
            ring->reset();
        }
        if (lane.source) {
            lane.source->seekToFrame(0);
        }
        lane.lifecycle.store(PlaybackLaneLifecycle::Inactive, std::memory_order_release);
    }
}

void AudioEngine::releasePlaybackResources() {
    m_isPlaying.store(false, std::memory_order_release);
    stopIoThread();
    stopHotJoinThread();
    resetMasterPlaybackTimeline();

    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
    clearPlaybackLanesLocked();
}

void AudioEngine::ensureIoThreadRunning() {
    if (m_ioRunning.load(std::memory_order_acquire)) return;
    m_ioRunning.store(true, std::memory_order_release);
    m_ioThread = std::thread(&AudioEngine::ioLoop, this);
}

void AudioEngine::stopIoThread() {
    if (!m_ioRunning.exchange(false, std::memory_order_acq_rel)) {
        return;
    }
    if (m_ioThread.joinable()) {
        m_ioThread.join();
    }
}

void AudioEngine::ioLoop() {
    std::vector<float> scratch;
    while (m_ioRunning.load(std::memory_order_acquire)) {
        if (!m_isPlaying.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(playback::kIoIdleSleepMs));
            continue;
        }

        bool progressed = false;
        const int64_t transportFrame =
            m_masterPlaybackFrame.load(std::memory_order_acquire);
        for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
            std::shared_ptr<IAudioSource> source;
            std::shared_ptr<RingBuffer> ring;
            int32_t channels = 0;
            bool laneExhausted = false;
            int64_t clipStartFrame = 0;
            int64_t clipEndFrame = 0;

            {
                std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
                PlaybackLaneSlot &lane = m_playbackLanes[laneIdx];
                const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
                if (!laneLifecycleParticipatesInMix(state)) {
                    continue;
                }
                clipStartFrame =
                    lane.clipTimelineStartFrame.load(std::memory_order_acquire);
                clipEndFrame =
                    lane.clipTimelineEndFrame.load(std::memory_order_acquire);
                if (!laneActiveOnTimeline(transportFrame, clipStartFrame, clipEndFrame)) {
                    if (clipEndFrame > 0 && transportFrame >= clipEndFrame) {
                        lane.sourceExhausted.store(true, std::memory_order_release);
                        lane.lifecycle.store(PlaybackLaneLifecycle::Exhausted,
                                             std::memory_order_release);
                    }
                    continue;
                }
                source = lane.source;
                ring = loadLaneRing(lane.ring);
                channels = lane.srcChannels.load(std::memory_order_acquire);
                laneExhausted = lane.sourceExhausted.load(std::memory_order_acquire);
            }

            if (!source || !ring || channels <= 0 || laneExhausted) {
                continue;
            }

            const std::size_t writableFloats = ring->writable();
            const std::size_t writableFrames = writableFloats / static_cast<std::size_t>(channels);
            if (writableFrames < static_cast<std::size_t>(playback::kIoBatchFrames)) {
                continue;
            }

            const std::size_t batchFloats =
                static_cast<std::size_t>(playback::kIoBatchFrames) * static_cast<std::size_t>(channels);
            if (scratch.size() < batchFloats) {
                scratch.resize(batchFloats);
            }

            const int32_t framesRead =
                source->readFrames(scratch.data(), playback::kIoBatchFrames);

            if (framesRead > 0) {
                ring->write(
                    scratch.data(),
                    static_cast<std::size_t>(framesRead) * static_cast<std::size_t>(channels));
                progressed = true;
            } else {
                markPlaybackLaneExhaustedLocked(laneIdx);
                progressed = true;
            }
        }

        if (!progressed) {
            std::this_thread::sleep_for(std::chrono::milliseconds(playback::kIoIdleSleepMs));
        }
    }
}

void AudioEngine::renderMaybeCompletePlaybackMaster(int32_t numFramesOutput,
                                                   int32_t /*outChannels*/,
                                                   int32_t minimumFramesReturnedFromLanes) {
    const int64_t sessionEndFrame =
        m_playbackSessionEndFrame.load(std::memory_order_acquire);
    if (sessionEndFrame > 0) {
        const int64_t transportFrame =
            m_masterPlaybackFrame.load(std::memory_order_acquire);
        if (transportFrame >= sessionEndFrame) {
            m_isPlaying.store(false, std::memory_order_release);
        }
        return;
    }

    if (minimumFramesReturnedFromLanes >= numFramesOutput) {
        return;
    }

    const int64_t transportFrame =
        m_masterPlaybackFrame.load(std::memory_order_acquire);

    bool allDrained = true;
    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
        const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
        if (!laneLifecycleParticipatesInCompletion(state)) {
            continue;
        }

        const int32_t srcChannels =
            m_playbackLanes[laneIdx].srcChannels.load(std::memory_order_acquire);
        if (srcChannels <= 0) {
            continue;
        }

        const int64_t clipStartFrame =
            m_playbackLanes[laneIdx].clipTimelineStartFrame.load(std::memory_order_acquire);
        const int64_t clipEndFrame =
            m_playbackLanes[laneIdx].clipTimelineEndFrame.load(std::memory_order_acquire);

        if (transportFrame < clipStartFrame) {
            allDrained = false;
            break;
        }
        if (clipEndFrame > 0 && transportFrame >= clipEndFrame) {
            continue;
        }

        const std::shared_ptr<RingBuffer> ring = loadLaneRing(m_playbackLanes[laneIdx].ring);
        if (!ring) {
            allDrained = false;
            break;
        }

        const bool exhausted =
            m_playbackLanes[laneIdx].sourceExhausted.load(std::memory_order_acquire);

        if (!exhausted || ring->readable() != 0) {
            allDrained = false;
            break;
        }
    }

    if (allDrained) {
        m_isPlaying.store(false, std::memory_order_release);
    }
}

void AudioEngine::render(float *outputInterleaved,
                         int32_t numFrames,
                         int32_t channels,
                         int32_t /*sampleRate*/) {
    if (!outputInterleaved || numFrames <= 0 || channels <= 0) {
        return;
    }

    const std::size_t outSampleCount = static_cast<std::size_t>(numFrames) *
                                       static_cast<std::size_t>(channels);
    std::fill(outputInterleaved, outputInterleaved + outSampleCount, 0.0f);

    if (!m_isPlaying.load(std::memory_order_acquire)) return;

    const int64_t transportFrameAtBlock =
        m_masterPlaybackFrame.load(std::memory_order_acquire);

    int32_t minFramesReturned = std::numeric_limits<int32_t>::max();
    bool readAnyLane = false;

    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
        const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
        if (!laneLifecycleParticipatesInMix(state)) {
            continue;
        }

        const int32_t srcChannels =
            m_playbackLanes[laneIdx].srcChannels.load(std::memory_order_acquire);
        if (srcChannels <= 0) {
            continue;
        }

        const int64_t clipStartFrame =
            m_playbackLanes[laneIdx].clipTimelineStartFrame.load(std::memory_order_acquire);
        const int64_t clipEndFrame =
            m_playbackLanes[laneIdx].clipTimelineEndFrame.load(std::memory_order_acquire);
        if (!laneActiveOnTimeline(transportFrameAtBlock, clipStartFrame, clipEndFrame)) {
            continue;
        }

        const std::shared_ptr<RingBuffer> ring =
            loadLaneRing(m_playbackLanes[laneIdx].ring);
        if (!ring) {
            continue;
        }

        const std::size_t neededFloats = static_cast<std::size_t>(numFrames) *
                                         static_cast<std::size_t>(srcChannels);
        const std::size_t scratchFloats = std::min(neededFloats, m_renderScratch.size());

        const std::size_t floatsRead =
            ring->read(m_renderScratch.data(), scratchFloats);
        const int32_t framesReturned =
            static_cast<int32_t>(floatsRead / static_cast<std::size_t>(srcChannels));

        readAnyLane = true;
        minFramesReturned = std::min(minFramesReturned, framesReturned);

        const bool audible =
            m_playbackLanes[laneIdx].audibleEnabled.load(std::memory_order_acquire);
        if (audible) {
            const float gain =
                m_playbackLanes[laneIdx].gain.load(std::memory_order_acquire);
            for (int32_t frame = 0; frame < framesReturned; ++frame) {
                const std::size_t srcBase =
                    static_cast<std::size_t>(frame) * static_cast<std::size_t>(srcChannels);
                for (int32_t outCh = 0; outCh < channels; ++outCh) {
                    const int32_t sourceChannel = std::min(outCh, srcChannels - 1);
                    outputInterleaved[static_cast<std::size_t>(frame * channels + outCh)] +=
                        m_renderScratch[srcBase + static_cast<std::size_t>(sourceChannel)] * gain;
                }
            }
        }
    }

    // Clock.3: playback-active writer for transport (recordLoop writes when !m_isPlaying).
    if (m_isPlaying.load(std::memory_order_acquire)) {
        m_masterPlaybackFrame.fetch_add(static_cast<int64_t>(numFrames),
                                        std::memory_order_release);
    }

    if (readAnyLane) {
        playback::ProcessMasterSafetySoftClip(outputInterleaved, outSampleCount);

        const int32_t reportedMin =
            (minFramesReturned == std::numeric_limits<int32_t>::max()) ? numFrames : minFramesReturned;
        renderMaybeCompletePlaybackMaster(numFrames, channels, reportedMin);
    }
}

// ---------------------------------------------------------------------------
// HJ.2 hot-join (async prepare + commit at transportFrame)
// ---------------------------------------------------------------------------

void AudioEngine::clearHotJoinStagingLocked(const std::size_t laneIndex) {
    if (laneIndex >= kPlaybackLaneProductCap) {
        return;
    }
    HotJoinStagingSlot &staging = m_hotJoinStaging[laneIndex];
    staging.ring.reset();
    staging.source.reset();
    staging.path.clear();
    staging.gain = 1.0f;
    staging.channels = 0;
    staging.clipStartMs = 0;
    staging.clipDurationMs = 0;
}

void AudioEngine::ensureHotJoinThreadRunning() {
    if (m_hotJoinRunning.load(std::memory_order_acquire)) {
        return;
    }
    m_hotJoinRunning.store(true, std::memory_order_release);
    m_hotJoinThread = std::thread(&AudioEngine::hotJoinThreadLoop, this);
}

void AudioEngine::stopHotJoinThread() {
    if (!m_hotJoinRunning.exchange(false, std::memory_order_acq_rel)) {
        return;
    }
    {
        std::lock_guard<std::mutex> lock(m_hotJoinMutex);
        m_hotJoinQueue.clear();
    }
    m_hotJoinCv.notify_all();
    if (m_hotJoinThread.joinable()) {
        m_hotJoinThread.join();
    }
}

void AudioEngine::enqueueHotJoinWork(HotJoinWorkItem item) {
    {
        std::lock_guard<std::mutex> lock(m_hotJoinMutex);
        m_hotJoinQueue.push_back(std::move(item));
    }
    m_hotJoinCv.notify_one();
}

bool AudioEngine::prepareHotJoinStagingLocked(const std::size_t laneIndex,
                                              const HotJoinWorkItem &item) {
    if (laneIndex >= kPlaybackLaneProductCap) {
        return false;
    }
    if (loadLaneLifecycle(laneIndex) != PlaybackLaneLifecycle::Preparing) {
        return false;
    }

    auto source = std::make_shared<LocalWavSource>(item.wavPath);
    if (!source->open()) {
        return false;
    }
    if (source->sampleRate() != m_sampleRate) {
        return false;
    }
    if (source->channelCount() < 1 || source->channelCount() > 2) {
        return false;
    }

    const int32_t ringFrameCapacity = computeRingFramesForSampleRate(m_sampleRate);
    const std::size_t ringFloats = static_cast<std::size_t>(ringFrameCapacity) *
                                   static_cast<std::size_t>(source->channelCount());
    HotJoinStagingSlot &staging = m_hotJoinStaging[laneIndex];
    staging.source = std::move(source);
    staging.ring = std::make_shared<RingBuffer>(ringFloats);
    staging.path = item.wavPath;
    staging.gain = item.gain;
    staging.channels = staging.source->channelCount();
    staging.clipStartMs = item.clipStartMs;
    staging.clipDurationMs = item.clipDurationMs;

    if (loadLaneLifecycle(laneIndex) != PlaybackLaneLifecycle::Preparing) {
        clearHotJoinStagingLocked(laneIndex);
        return false;
    }

    m_playbackLanes[laneIndex].lifecycle.store(PlaybackLaneLifecycle::ReadyToCommit,
                                               std::memory_order_release);
    return true;
}

bool AudioEngine::commitHotJoinLaneLocked(const std::size_t laneIndex) {
    if (laneIndex >= kPlaybackLaneProductCap) {
        return false;
    }
    if (!m_isPlaying.load(std::memory_order_acquire)) {
        return false;
    }
    if (loadLaneLifecycle(laneIndex) != PlaybackLaneLifecycle::ReadyToCommit) {
        return false;
    }
    if (!m_playbackLanes[laneIndex].hotJoinPublishAudible.load(std::memory_order_acquire)) {
        return false;
    }

    HotJoinStagingSlot &staging = m_hotJoinStaging[laneIndex];
    if (!staging.source || !staging.ring) {
        return false;
    }

    const int64_t clipStartFrame =
        playbackStartFrameFromMs(staging.clipStartMs, m_sampleRate);
    const int64_t clipEndFrame =
        clipTimelineEndFrameForLane(clipStartFrame, staging.clipDurationMs, m_sampleRate);
    const int64_t commitFrame = transportFrame();

    if (clipEndFrame > 0 && commitFrame >= clipEndFrame) {
        clearHotJoinStagingLocked(laneIndex);
        setLaneInactiveLocked(m_playbackLanes[laneIndex]);
        return false;
    }

    const int64_t sourceSeekFrame = laneSourceSeekFrame(commitFrame, clipStartFrame);
    if (!seekSourceToStartFrame(*staging.source, sourceSeekFrame)) {
        return false;
    }

    const bool beforeClipStart = commitFrame < clipStartFrame;
    const bool pastClipEnd = clipEndFrame > 0 && commitFrame >= clipEndFrame;
    bool exhaustedAtStart = false;
    if (pastClipEnd) {
        exhaustedAtStart = true;
    } else if (beforeClipStart) {
        exhaustedAtStart = false;
    } else {
        exhaustedAtStart = isSourceExhaustedAtStart(*staging.source, sourceSeekFrame);
    }
    staging.ring->reset();

    const bool activeOnTimeline =
        laneActiveOnTimeline(commitFrame, clipStartFrame, clipEndFrame);
    if (!exhaustedAtStart && activeOnTimeline) {
        const int32_t prerollTargetFrames = computePrerollFramesForSampleRate(m_sampleRate);
        std::vector<float> preroll(static_cast<std::size_t>(prerollTargetFrames) *
                                   static_cast<std::size_t>(staging.channels));
        const int32_t prerollFrames =
            staging.source->readFrames(preroll.data(), prerollTargetFrames);
        if (prerollFrames < 0) {
            return false;
        }
        if (prerollFrames > 0) {
            staging.ring->write(
                preroll.data(),
                static_cast<std::size_t>(prerollFrames) *
                    static_cast<std::size_t>(staging.channels));
        }
    }

    if (!m_isPlaying.load(std::memory_order_acquire)) {
        return false;
    }
    if (loadLaneLifecycle(laneIndex) != PlaybackLaneLifecycle::ReadyToCommit) {
        return false;
    }
    if (!m_playbackLanes[laneIndex].hotJoinPublishAudible.load(std::memory_order_acquire)) {
        return false;
    }

    PlaybackLaneSlot &lane = m_playbackLanes[laneIndex];
    assert(!loadLaneRing(lane.ring) && "hot-join commit expects an empty lane ring slot");
    lane.source = staging.source;
    storeLaneRing(lane.ring, std::move(staging.ring));
    lane.currentPath = staging.path;
    lane.gain.store(staging.gain, std::memory_order_release);
    const bool publishAudible =
        m_playbackLanes[laneIndex].hotJoinPublishAudible.load(std::memory_order_acquire);
    lane.audibleEnabled.store(publishAudible, std::memory_order_release);
    lane.sourceExhausted.store(exhaustedAtStart, std::memory_order_release);
    lane.srcChannels.store(staging.channels, std::memory_order_release);
    lane.clipTimelineStartFrame.store(clipStartFrame, std::memory_order_release);
    lane.clipTimelineEndFrame.store(clipEndFrame, std::memory_order_release);
    lane.lifecycle.store(
        exhaustedAtStart ? PlaybackLaneLifecycle::Exhausted : PlaybackLaneLifecycle::Active,
        std::memory_order_release);

    staging.source.reset();
    staging.path.clear();
    staging.gain = 1.0f;
    staging.channels = 0;
    return true;
}

void AudioEngine::hotJoinThreadLoop() {
    while (m_hotJoinRunning.load(std::memory_order_acquire)) {
        HotJoinWorkItem item;
        {
            std::unique_lock<std::mutex> lock(m_hotJoinMutex);
            m_hotJoinCv.wait(lock, [this] {
                return !m_hotJoinRunning.load(std::memory_order_acquire) ||
                       !m_hotJoinQueue.empty();
            });
            if (!m_hotJoinRunning.load(std::memory_order_acquire) && m_hotJoinQueue.empty()) {
                break;
            }
            if (m_hotJoinQueue.empty()) {
                continue;
            }
            item = std::move(m_hotJoinQueue.front());
            m_hotJoinQueue.pop_front();
        }

        bool prepared = false;
        {
            std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
            prepared = prepareHotJoinStagingLocked(item.laneIndex, item);
            if (!prepared) {
                clearHotJoinStagingLocked(item.laneIndex);
                if (loadLaneLifecycle(item.laneIndex) == PlaybackLaneLifecycle::Preparing) {
                    setLaneInactiveLocked(m_playbackLanes[item.laneIndex]);
                }
            }
        }

        if (!prepared) {
            continue;
        }

        bool committed = false;
        {
            std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
            committed = commitHotJoinLaneLocked(item.laneIndex);
            if (!committed) {
                clearHotJoinStagingLocked(item.laneIndex);
                setLaneInactiveLocked(m_playbackLanes[item.laneIndex]);
            }
        }
    }
}

int32_t AudioEngine::beginHotJoinLane(const std::string &wavPath,
                                      const float gain,
                                      const int64_t clipStartMs,
                                      const int64_t clipDurationMs) {
    if (wavPath.empty() || !m_isPlaying.load(std::memory_order_acquire)) {
        return -1;
    }

    HotJoinWorkItem item;
    {
        std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
        if (!m_isPlaying.load(std::memory_order_acquire)) {
            return -1;
        }

        std::size_t reservedLane = kPlaybackLaneProductCap;
        for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneProductCap; ++laneIdx) {
            const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
            const bool slotAvailable =
                (state == PlaybackLaneLifecycle::Inactive ||
                 state == PlaybackLaneLifecycle::Cancelled) &&
                m_playbackLanes[laneIdx].srcChannels.load(std::memory_order_acquire) == 0;
            if (slotAvailable) {
                reservedLane = laneIdx;
                break;
            }
        }
        if (reservedLane >= kPlaybackLaneProductCap) {
            return -1;
        }

        item.laneIndex = reservedLane;
        item.wavPath = wavPath;
        item.gain = gain;
        item.clipStartMs = std::max<int64_t>(0, clipStartMs);
        item.clipDurationMs = std::max<int64_t>(0, clipDurationMs);
        m_playbackLanes[reservedLane].gain.store(gain, std::memory_order_release);
        m_playbackLanes[reservedLane].hotJoinPublishAudible.store(true,
                                                                  std::memory_order_release);
        m_playbackLanes[reservedLane].lifecycle.store(PlaybackLaneLifecycle::Preparing,
                                                      std::memory_order_release);
    }

    ensureHotJoinThreadRunning();
    const int32_t reservedLaneIndex = static_cast<int32_t>(item.laneIndex);
    enqueueHotJoinWork(std::move(item));
    return reservedLaneIndex;
}

void AudioEngine::cancelHotJoinLane(const std::size_t laneIndex) {
    if (laneIndex >= kPlaybackLaneProductCap) {
        return;
    }

    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
    PlaybackLaneSlot &lane = m_playbackLanes[laneIndex];
    lane.hotJoinPublishAudible.store(false, std::memory_order_release);

    const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIndex);
    if (state == PlaybackLaneLifecycle::Preparing ||
        state == PlaybackLaneLifecycle::ReadyToCommit ||
        state == PlaybackLaneLifecycle::Cancelled) {
        clearHotJoinStagingLocked(laneIndex);
        setLaneInactiveLocked(lane);
    } else if (state == PlaybackLaneLifecycle::Active) {
        lane.audibleEnabled.store(false, std::memory_order_release);
    }
}

PlaybackLaneLifecycle AudioEngine::laneLifecycle(const std::size_t laneIndex) const {
    return loadLaneLifecycle(laneIndex);
}


} // namespace dawengine
