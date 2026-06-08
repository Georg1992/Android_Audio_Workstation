#include "AudioEngine.h"

#include <cassert>
#include <android/log.h>
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

int64_t positiveModulo(int64_t value, int64_t modulus) {
    if (modulus <= 0) return 0;
    const int64_t remainder = value % modulus;
    return remainder >= 0 ? remainder : remainder + modulus;
}

int64_t laneSourceSeekFrameForArm(int64_t transportStartFrame,
                                  int64_t clipStartFrame,
                                  bool loopEnabled,
                                  int64_t loopSourceStartFrame,
                                  int64_t loopSourceEndFrame) {
    if (!loopEnabled) {
        return laneSourceSeekFrame(transportStartFrame, clipStartFrame);
    }
    const int64_t loopLength = loopSourceEndFrame - loopSourceStartFrame;
    if (loopLength <= 0) return loopSourceStartFrame;
    // Loop sessions share one global transport; clip timeline placement must not phase-wrap.
    return loopSourceStartFrame +
           positiveModulo(transportStartFrame, loopLength);
}

bool laneActiveOnTimelineForPlayback(int64_t transportFrame,
                                     int64_t clipStartFrame,
                                     int64_t clipEndFrame,
                                     bool loopEnabled) {
    if (loopEnabled) {
        (void)transportFrame;
        (void)clipStartFrame;
        (void)clipEndFrame;
        return true;
    }
    return laneActiveOnTimeline(transportFrame, clipStartFrame, clipEndFrame);
}

int64_t clampLoopEndFrameToSource(int64_t loopEndFrame, int64_t totalFrames) {
    if (loopEndFrame <= 0) return loopEndFrame;
    if (totalFrames <= 0) return loopEndFrame;
    return std::min(loopEndFrame, totalFrames);
}

bool shouldWrapLoopSourcePosition(int64_t pos,
                                  int64_t effectiveLoopEndFrame,
                                  int64_t totalFrames) {
    if (pos >= effectiveLoopEndFrame) return true;
    return totalFrames > 0 && pos >= totalFrames;
}

int32_t readLaneSourceFrames(dawengine::IAudioSource &source,
                             float *dst,
                             int32_t maxFrames,
                             int32_t channels,
                             bool loopEnabled,
                             int64_t loopStartFrame,
                             int64_t loopEndFrame) {
    if (maxFrames <= 0 || channels <= 0) return 0;
    if (!loopEnabled) {
        return source.readFrames(dst, maxFrames);
    }
    if (loopEndFrame <= loopStartFrame) return 0;

    const int64_t totalFrames = source.totalFrames();
    const int64_t effectiveLoopEndFrame =
        clampLoopEndFrameToSource(loopEndFrame, totalFrames);

    int32_t framesWritten = 0;
    while (framesWritten < maxFrames) {
        int64_t pos = source.currentFrame();
        if (pos < 0) {
            return source.readFrames(dst, maxFrames);
        }
        if (shouldWrapLoopSourcePosition(pos, effectiveLoopEndFrame, totalFrames)) {
            if (!source.seekToFrame(loopStartFrame)) {
                return framesWritten > 0 ? framesWritten : 0;
            }
        }
        pos = source.currentFrame();
        const int64_t framesUntilLoopEnd = effectiveLoopEndFrame - pos;
        const int32_t batchMax = static_cast<int32_t>(std::min<int64_t>(
            maxFrames - framesWritten,
            framesUntilLoopEnd));
        if (batchMax <= 0) {
            if (!source.seekToFrame(loopStartFrame)) {
                return framesWritten > 0 ? framesWritten : 0;
            }
            continue;
        }
        const int32_t framesRead = source.readFrames(
            dst + static_cast<std::size_t>(framesWritten) *
                      static_cast<std::size_t>(channels),
            batchMax);
        if (framesRead < 0) return framesRead;
        if (framesRead == 0) {
            if (shouldWrapLoopSourcePosition(
                    source.currentFrame(), effectiveLoopEndFrame, totalFrames)) {
                if (!source.seekToFrame(loopStartFrame)) {
                    return framesWritten > 0 ? framesWritten : 0;
                }
                continue;
            }
            break;
        }
        framesWritten += framesRead;
    }
    return framesWritten;
}

void mixLaneSampleWithPan(float gain,
                          float pan,
                          int32_t srcChannels,
                          const float *srcFrame,
                          float *outFrame,
                          int32_t outChannels) {
    const float panClamped = std::clamp(pan, -1.0f, 1.0f);
    const float angle = (panClamped + 1.0f) * 0.25f * static_cast<float>(M_PI);
    const float leftGain = gain * std::cos(angle);
    const float rightGain = gain * std::sin(angle);
    const float leftIn = srcFrame[0];
    const float rightIn = srcChannels >= 2 ? srcFrame[1] : srcFrame[0];
    if (outChannels >= 1) {
        outFrame[0] += leftIn * leftGain;
    }
    if (outChannels >= 2) {
        outFrame[1] += rightIn * rightGain;
    }
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

constexpr int32_t kMaxRenderFramesPerCallback = 8'192;
constexpr int32_t kOfflineMixdownBlockFrames = 8'192;
constexpr std::size_t kRenderScratchFloatCount =
    static_cast<std::size_t>(kMaxRenderFramesPerCallback) * 2u;

constexpr const char *kMixdownLogTag = "AudioMixdown";

class StreamingPcm16WavWriter {
public:
    bool Open(const std::string &path, int32_t sampleRateHz, int32_t channelCount) {
        if (path.empty() || sampleRateHz <= 0) return false;
        file_ = std::fopen(path.c_str(), "wb");
        if (!file_) return false;
        sampleRateHz_ = sampleRateHz;
        channelCount_ = static_cast<uint16_t>(std::max(1, channelCount));
        const uint32_t bytesPerSample = kWavBitsPerSample / 8u;
        const uint32_t byteRate =
            static_cast<uint32_t>(sampleRateHz_) * channelCount_ * bytesPerSample;
        const uint16_t blockAlign =
            static_cast<uint16_t>(channelCount_ * bytesPerSample);

        std::fwrite("RIFF", 1, 4, file_);
        WriteUint32LE(file_, 36u);
        std::fwrite("WAVE", 1, 4, file_);
        std::fwrite("fmt ", 1, 4, file_);
        WriteUint32LE(file_, 16u);
        WriteUint16LE(file_, 1u);
        WriteUint16LE(file_, channelCount_);
        WriteUint32LE(file_, static_cast<uint32_t>(sampleRateHz_));
        WriteUint32LE(file_, byteRate);
        WriteUint16LE(file_, blockAlign);
        WriteUint16LE(file_, kWavBitsPerSample);
        std::fwrite("data", 1, 4, file_);
        WriteUint32LE(file_, 0u);
        dataBytesWritten_ = 0;
        return std::ferror(file_) == 0;
    }

    bool WriteFloatInterleaved(const float *samples, std::size_t sampleCount) {
        if (!file_ || !samples) return false;
        for (std::size_t i = 0; i < sampleCount; ++i) {
            const int16_t pcm16 = FloatToPcm16(samples[i]);
            if (std::fwrite(&pcm16, sizeof(pcm16), 1, file_) != 1u) {
                return false;
            }
        }
        dataBytesWritten_ += static_cast<uint32_t>(sampleCount * sizeof(int16_t));
        return std::ferror(file_) == 0;
    }

    bool Finalize() {
        if (!file_) return false;
        const long dataSizePos = std::ftell(file_);
        if (dataSizePos < 0) return false;
        if (std::fseek(file_, 4, SEEK_SET) != 0) return false;
        WriteUint32LE(file_, 36u + dataBytesWritten_);
        if (std::fseek(file_, 40, SEEK_SET) != 0) return false;
        WriteUint32LE(file_, dataBytesWritten_);
        const bool ok = std::ferror(file_) == 0;
        std::fclose(file_);
        file_ = nullptr;
        return ok && dataBytesWritten_ > 0u;
    }

    void Abort() {
        if (file_) {
            std::fclose(file_);
            file_ = nullptr;
        }
        dataBytesWritten_ = 0;
    }

    uint32_t dataBytesWritten() const { return dataBytesWritten_; }

private:
    FILE *file_ = nullptr;
    uint32_t dataBytesWritten_ = 0;
    int32_t sampleRateHz_ = 44'100;
    uint16_t channelCount_ = 2;
};

// Master safety soft-clip knee (~-0.09 dBFS). UI yellow lamp uses this same value:
// held pre-soft-clip peak >= kMasterSafetyThreshold means soft clip has engaged.
// Yellow is not hard clipping — it signals the safety stage is active on this peak hold.
constexpr float kMasterSafetyThreshold = 0.99f;
constexpr float kMasterSafetyHeadroom = 1.0f - kMasterSafetyThreshold;
constexpr bool kMasterSafetySoftClipEnabled = true;

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

float PeakAbsInterleaved(const float *interleaved, std::size_t sampleCount) {
    float peak = 0.0f;
    for (std::size_t i = 0; i < sampleCount; ++i) {
        const float magnitude =
            interleaved[i] < 0.0f ? -interleaved[i] : interleaved[i];
        if (magnitude > peak) {
            peak = magnitude;
        }
    }
    return peak;
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
    lane.pan.store(0.0f, std::memory_order_release);
    lane.srcChannels.store(0, std::memory_order_release);
    lane.clipTimelineStartFrame.store(0, std::memory_order_release);
    lane.clipTimelineEndFrame.store(0, std::memory_order_release);
    lane.loopEnabled.store(false, std::memory_order_release);
    lane.sourceLoopStartFrame.store(0, std::memory_order_release);
    lane.sourceLoopEndFrame.store(0, std::memory_order_release);
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
                                           const bool reuseExistingSourceOnSamePath,
                                           const bool loopEnabled,
                                           const int64_t loopSourceStartMs,
                                           const int64_t loopSourceEndMs,
                                           const float lanePan) {
    if (laneIndex >= kPlaybackLaneProductCap || wavPath.empty()) {
        return false;
    }

    const int64_t clipStartFrame = playbackStartFrameFromMs(clipStartMs, m_sampleRate);
    const int64_t clipEndFrame =
        loopEnabled
            ? 0
            : clipTimelineEndFrameForLane(clipStartFrame, clipDurationMs, m_sampleRate);
    int64_t sourceLoopStartFrame =
        loopEnabled ? playbackStartFrameFromMs(loopSourceStartMs, m_sampleRate) : 0;
    int64_t sourceLoopEndFrame =
        loopEnabled ? playbackStartFrameFromMs(loopSourceEndMs, m_sampleRate) : 0;
    const int64_t sourceSeekFrame =
        laneSourceSeekFrameForArm(
            transportStartFrame,
            clipStartFrame,
            loopEnabled,
            sourceLoopStartFrame,
            sourceLoopEndFrame);
    const bool activeOnTimeline =
        laneActiveOnTimelineForPlayback(
            transportStartFrame, clipStartFrame, clipEndFrame, loopEnabled);
    const bool beforeClipStart =
        !loopEnabled && transportStartFrame < clipStartFrame;
    const bool pastClipEnd =
        !loopEnabled && clipEndFrame > 0 && transportStartFrame >= clipEndFrame;

    PlaybackLaneSlot &lane = m_playbackLanes[laneIndex];
    lane.gain.store(laneGain, std::memory_order_release);
    lane.pan.store(std::clamp(lanePan, -1.0f, 1.0f), std::memory_order_release);
    lane.audibleEnabled.store(true, std::memory_order_release);
    lane.clipTimelineStartFrame.store(clipStartFrame, std::memory_order_release);
    lane.clipTimelineEndFrame.store(clipEndFrame, std::memory_order_release);
    lane.loopEnabled.store(loopEnabled, std::memory_order_release);
    lane.sourceLoopStartFrame.store(sourceLoopStartFrame, std::memory_order_release);
    lane.sourceLoopEndFrame.store(sourceLoopEndFrame, std::memory_order_release);

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
        } else if (loopEnabled) {
            exhaustedAtStart = sourceLoopEndFrame <= sourceLoopStartFrame;
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
        } else if (loopEnabled) {
            exhaustedAtStart = sourceLoopEndFrame <= sourceLoopStartFrame;
        } else {
            exhaustedAtStart = isSourceExhaustedAtStart(*lane.source, sourceSeekFrame);
        }
    }

    if (loopEnabled && lane.source) {
        const int64_t totalFrames = lane.source->totalFrames();
        sourceLoopEndFrame =
            clampLoopEndFrameToSource(sourceLoopEndFrame, totalFrames);
        if (totalFrames > 0 && sourceLoopStartFrame > totalFrames) {
            sourceLoopStartFrame = totalFrames;
        }
        lane.sourceLoopStartFrame.store(sourceLoopStartFrame, std::memory_order_release);
        lane.sourceLoopEndFrame.store(sourceLoopEndFrame, std::memory_order_release);
        if (!pastClipEnd && !beforeClipStart) {
            exhaustedAtStart = sourceLoopEndFrame <= sourceLoopStartFrame;
        }
    }

    lane.sourceExhausted.store(exhaustedAtStart, std::memory_order_release);
    lane.lifecycle.store(PlaybackLaneLifecycle::Active, std::memory_order_release);

    if (!exhaustedAtStart && !beforeClipStart && activeOnTimeline) {
        const int32_t channels = lane.source->channelCount();
        std::vector<float> preroll(static_cast<std::size_t>(prerollTargetFrames) *
                                   static_cast<std::size_t>(channels));
        const int32_t prerollFrames =
            loopEnabled
                ? readLaneSourceFrames(
                      *lane.source,
                      preroll.data(),
                      prerollTargetFrames,
                      channels,
                      true,
                      sourceLoopStartFrame,
                      sourceLoopEndFrame)
                : lane.source->readFrames(preroll.data(), prerollTargetFrames);
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
                                         const std::vector<int64_t> &laneClipDurationMs,
                                         const std::vector<uint8_t> &laneLoopEnabled,
                                         const std::vector<int64_t> &laneLoopSourceStartMs,
                                         const std::vector<int64_t> &laneLoopSourceEndMs,
                                         const std::vector<float> &lanePan) {
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
        const bool loopEnabled =
            !laneLoopEnabled.empty() && laneLoopEnabled[0] != 0;
        const int64_t loopSourceStartMs =
            laneLoopSourceStartMs.empty() ? 0 : laneLoopSourceStartMs[0];
        const int64_t loopSourceEndMs =
            laneLoopSourceEndMs.empty() ? 0 : laneLoopSourceEndMs[0];
        const float pan =
            lanePan.empty() ? 0.0f : lanePan[0];
        if (!armOnePlaybackLaneLocked(
                0,
                wavPaths[0],
                gains[0],
                transportStartFrame,
                clipStartMs,
                clipDurationMs,
                true,
                loopEnabled,
                loopSourceStartMs,
                loopSourceEndMs,
                pan)) {
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
        const bool loopEnabled =
            laneIdx < laneLoopEnabled.size() && laneLoopEnabled[laneIdx] != 0;
        const int64_t loopSourceStartMs =
            laneIdx < laneLoopSourceStartMs.size() ? laneLoopSourceStartMs[laneIdx] : 0;
        const int64_t loopSourceEndMs =
            laneIdx < laneLoopSourceEndMs.size() ? laneLoopSourceEndMs[laneIdx] : 0;
        const float pan =
            laneIdx < lanePan.size() ? lanePan[laneIdx] : 0.0f;
        if (!armOnePlaybackLaneLocked(
                laneIdx,
                wavPaths[laneIdx],
                gains[laneIdx],
                transportStartFrame,
                clipStartMs,
                clipDurationMs,
                false,
                loopEnabled,
                loopSourceStartMs,
                loopSourceEndMs,
                pan)) {
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
    m_recordingFirstSampleTransportFrame.store(kRecordingFirstSampleTransportUnset,
                                               std::memory_order_release);

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

        int64_t unset = kRecordingFirstSampleTransportUnset;
        m_recordingFirstSampleTransportFrame.compare_exchange_strong(
            unset,
            m_masterPlaybackFrame.load(std::memory_order_acquire),
            std::memory_order_release);

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

int64_t AudioEngine::recordingFirstSampleTransportPositionMs() const {
    const int64_t frame = recordingFirstSampleTransportFrame();
    if (frame < 0) return kRecordingFirstSampleTransportUnset;
    const int32_t rate = m_sampleRate;
    if (rate <= 0) return kRecordingFirstSampleTransportUnset;
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
    m_sessionHasLoopLanes.store(false, std::memory_order_release);
}

bool AudioEngine::setPlaybackSources(const std::vector<std::string> &wavPaths,
                                     const std::vector<float> &gains,
                                     int64_t startPositionMs,
                                     int64_t sessionTimelineEndMs,
                                     const std::vector<int64_t> &laneClipStartMs,
                                     const std::vector<int64_t> &laneClipDurationMs,
                                     const std::vector<uint8_t> &laneLoopEnabled,
                                     const std::vector<int64_t> &laneLoopSourceStartMs,
                                     const std::vector<int64_t> &laneLoopSourceEndMs,
                                     const std::vector<float> &lanePan) {
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
                wavPaths,
                gains,
                startFrame,
                laneClipStartMs,
                laneClipDurationMs,
                laneLoopEnabled,
                laneLoopSourceStartMs,
                laneLoopSourceEndMs,
                lanePan)) {
            resetMasterPlaybackTimeline();
            return false;
        }
        bool sessionHasLoopLanes = false;
        for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneProductCap; ++laneIdx) {
            const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
            if (state != PlaybackLaneLifecycle::Active &&
                state != PlaybackLaneLifecycle::Exhausted) {
                continue;
            }
            if (m_playbackLanes[laneIdx].loopEnabled.load(std::memory_order_acquire)) {
                sessionHasLoopLanes = true;
                break;
            }
        }
        m_sessionHasLoopLanes.store(sessionHasLoopLanes, std::memory_order_release);
    }

    initializeMasterPlaybackTimeline(startFrame);
    ensureIoThreadRunning();
    ensureHotJoinThreadRunning();
    m_masterPeakHoldLinear.store(0.0f, std::memory_order_release);
    m_isPlaying.store(true, std::memory_order_release);
    return true;
}

void AudioEngine::setPlaybackLaneGain(const std::size_t laneIndex, const float gain) {
    if (laneIndex >= kPlaybackLaneProductCap) {
        return;
    }
    const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIndex);
    if (state == PlaybackLaneLifecycle::Inactive ||
        state == PlaybackLaneLifecycle::Cancelled) {
        return;
    }
    const float clampedGain = std::clamp(gain, 0.0f, 1.0f);
    m_playbackLanes[laneIndex].gain.store(clampedGain, std::memory_order_release);
}

void AudioEngine::setPlaybackLanePan(const std::size_t laneIndex, const float pan) {
    if (laneIndex >= kPlaybackLaneProductCap) {
        return;
    }
    const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIndex);
    if (state == PlaybackLaneLifecycle::Inactive ||
        state == PlaybackLaneLifecycle::Cancelled) {
        return;
    }
    const float clampedPan = std::clamp(pan, -1.0f, 1.0f);
    m_playbackLanes[laneIndex].pan.store(clampedPan, std::memory_order_release);
}

void AudioEngine::resetMasterPeakHold() {
    m_masterPeakHoldLinear.store(0.0f, std::memory_order_release);
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
    m_masterPeakHoldLinear.store(0.0f, std::memory_order_release);
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
            bool loopEnabled = false;
            int64_t sourceLoopStartFrame = 0;
            int64_t sourceLoopEndFrame = 0;

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
                loopEnabled =
                    lane.loopEnabled.load(std::memory_order_acquire);
                sourceLoopStartFrame =
                    lane.sourceLoopStartFrame.load(std::memory_order_acquire);
                sourceLoopEndFrame =
                    lane.sourceLoopEndFrame.load(std::memory_order_acquire);
                if (!laneActiveOnTimelineForPlayback(
                        transportFrame, clipStartFrame, clipEndFrame, loopEnabled)) {
                    if (!loopEnabled && clipEndFrame > 0 &&
                        transportFrame >= clipEndFrame) {
                        lane.sourceExhausted.store(true, std::memory_order_release);
                        lane.lifecycle.store(PlaybackLaneLifecycle::Exhausted,
                                             std::memory_order_release);
                    }
                    if (transportFrame < clipStartFrame) {
                        if (std::shared_ptr<RingBuffer> laneRing = loadLaneRing(lane.ring)) {
                            laneRing->reset();
                        }
                        if (lane.source) {
                            const int64_t parkedFrame =
                                laneSourceSeekFrameForArm(
                                    transportFrame,
                                    clipStartFrame,
                                    loopEnabled,
                                    sourceLoopStartFrame,
                                    sourceLoopEndFrame);
                            seekSourceToStartFrame(*lane.source, parkedFrame);
                        }
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
                readLaneSourceFrames(
                    *source,
                    scratch.data(),
                    playback::kIoBatchFrames,
                    channels,
                    loopEnabled,
                    sourceLoopStartFrame,
                    sourceLoopEndFrame);

            if (framesRead > 0) {
                ring->write(
                    scratch.data(),
                    static_cast<std::size_t>(framesRead) * static_cast<std::size_t>(channels));
                progressed = true;
            } else if (!loopEnabled) {
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
    if (m_sessionHasLoopLanes.load(std::memory_order_acquire)) {
        return;
    }

    bool hasLoopLane = false;
    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
        const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
        if (!laneLifecycleParticipatesInCompletion(state)) {
            continue;
        }
        if (m_playbackLanes[laneIdx].loopEnabled.load(std::memory_order_acquire)) {
            hasLoopLane = true;
            break;
        }
    }

    const int64_t sessionEndFrame =
        m_playbackSessionEndFrame.load(std::memory_order_acquire);
    if (sessionEndFrame > 0 && !hasLoopLane) {
        const int64_t transportFrame =
            m_masterPlaybackFrame.load(std::memory_order_acquire);
        if (transportFrame >= sessionEndFrame) {
            m_isPlaying.store(false, std::memory_order_release);
        }
        return;
    }

    if (hasLoopLane) {
        return;
    }

    bool anyActiveLoopLane = false;
    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
        const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
        if (state != PlaybackLaneLifecycle::Active) {
            continue;
        }
        if (!m_playbackLanes[laneIdx].loopEnabled.load(std::memory_order_acquire)) {
            continue;
        }
        if (m_playbackLanes[laneIdx].srcChannels.load(std::memory_order_acquire) <= 0) {
            continue;
        }
        if (m_playbackLanes[laneIdx].sourceExhausted.load(std::memory_order_acquire)) {
            continue;
        }
        anyActiveLoopLane = true;
        break;
    }
    if (anyActiveLoopLane) {
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

        if (m_playbackLanes[laneIdx].loopEnabled.load(std::memory_order_acquire)) {
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

bool AudioEngine::renderBlock(float *outputInterleaved,
                              const int32_t numFrames,
                              const int32_t outChannels,
                              const int64_t transportFrameAtBlock,
                              const RenderBlockInputMode inputMode,
                              const bool applyMasterSoftClip,
                              const bool playbackMutexAlreadyHeld,
                              int32_t *outMinFramesReturned) {
    if (!outputInterleaved || numFrames <= 0 || outChannels <= 0) {
        return false;
    }

    const std::size_t outSampleCount = static_cast<std::size_t>(numFrames) *
                                       static_cast<std::size_t>(outChannels);
    std::fill(outputInterleaved, outputInterleaved + outSampleCount, 0.0f);

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
        const bool loopEnabled =
            m_playbackLanes[laneIdx].loopEnabled.load(std::memory_order_acquire);

        bool anyActiveFrameInBlock = false;
        for (int32_t probeFrame = 0; probeFrame < numFrames; ++probeFrame) {
            if (laneActiveOnTimelineForPlayback(
                    transportFrameAtBlock + static_cast<int64_t>(probeFrame),
                    clipStartFrame,
                    clipEndFrame,
                    loopEnabled)) {
                anyActiveFrameInBlock = true;
                break;
            }
        }
        if (!anyActiveFrameInBlock) {
            continue;
        }

        int32_t framesReturned = 0;
        if (inputMode == RenderBlockInputMode::RingBuffer) {
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
            framesReturned =
                static_cast<int32_t>(floatsRead / static_cast<std::size_t>(srcChannels));
        } else {
            std::unique_ptr<std::lock_guard<std::mutex>> playbackLock;
            if (!playbackMutexAlreadyHeld) {
                playbackLock =
                    std::make_unique<std::lock_guard<std::mutex>>(m_playbackMutex);
            }
            IAudioSource *source = m_playbackLanes[laneIdx].source.get();
            if (!source) {
                continue;
            }
            const int64_t sourceLoopStartFrame =
                m_playbackLanes[laneIdx].sourceLoopStartFrame.load(std::memory_order_acquire);
            const int64_t sourceLoopEndFrame =
                m_playbackLanes[laneIdx].sourceLoopEndFrame.load(std::memory_order_acquire);
            const std::size_t neededFloats = static_cast<std::size_t>(numFrames) *
                                             static_cast<std::size_t>(srcChannels);
            if (m_renderScratch.size() < neededFloats) {
                m_renderScratch.resize(neededFloats);
            }
            framesReturned = readLaneSourceFrames(
                *source,
                m_renderScratch.data(),
                numFrames,
                srcChannels,
                loopEnabled,
                sourceLoopStartFrame,
                sourceLoopEndFrame);
            if (framesReturned < 0) {
                framesReturned = 0;
            }
        }

        if (framesReturned <= 0) {
            continue;
        }

        readAnyLane = true;
        minFramesReturned = std::min(minFramesReturned, framesReturned);

        const bool audible =
            m_playbackLanes[laneIdx].audibleEnabled.load(std::memory_order_acquire);
        if (!audible) {
            continue;
        }

        const float gain =
            m_playbackLanes[laneIdx].gain.load(std::memory_order_acquire);
        const float pan =
            m_playbackLanes[laneIdx].pan.load(std::memory_order_acquire);
        for (int32_t frame = 0; frame < framesReturned; ++frame) {
            const int64_t frameTransport =
                transportFrameAtBlock + static_cast<int64_t>(frame);
            if (!laneActiveOnTimelineForPlayback(
                    frameTransport, clipStartFrame, clipEndFrame, loopEnabled)) {
                continue;
            }
            const std::size_t srcBase =
                static_cast<std::size_t>(frame) * static_cast<std::size_t>(srcChannels);
            const std::size_t outBase =
                static_cast<std::size_t>(frame) * static_cast<std::size_t>(outChannels);
            mixLaneSampleWithPan(
                gain,
                pan,
                srcChannels,
                m_renderScratch.data() + srcBase,
                outputInterleaved + outBase,
                outChannels);
        }
    }

    if (readAnyLane && applyMasterSoftClip && playback::kMasterSafetySoftClipEnabled) {
        playback::ProcessMasterSafetySoftClip(outputInterleaved, outSampleCount);
    }

    if (outMinFramesReturned != nullptr) {
        *outMinFramesReturned =
            readAnyLane
                ? (minFramesReturned == std::numeric_limits<int32_t>::max()
                       ? numFrames
                       : minFramesReturned)
                : 0;
    }

    return readAnyLane;
}

void AudioEngine::syncLaneSourcesForOfflineTransport(const int64_t transportFrameAtBlock) {
    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
        PlaybackLaneSlot &lane = m_playbackLanes[laneIdx];
        const PlaybackLaneLifecycle state = loadLaneLifecycle(laneIdx);
        if (!laneLifecycleParticipatesInMix(state) || !lane.source) {
            continue;
        }

        const int64_t clipStartFrame =
            lane.clipTimelineStartFrame.load(std::memory_order_acquire);
        const int64_t clipEndFrame =
            lane.clipTimelineEndFrame.load(std::memory_order_acquire);
        const bool loopEnabled = lane.loopEnabled.load(std::memory_order_acquire);
        const int64_t sourceLoopStartFrame =
            lane.sourceLoopStartFrame.load(std::memory_order_acquire);
        const int64_t sourceLoopEndFrame =
            lane.sourceLoopEndFrame.load(std::memory_order_acquire);

        const int64_t parkedFrame = laneSourceSeekFrameForArm(
            transportFrameAtBlock,
            clipStartFrame,
            loopEnabled,
            sourceLoopStartFrame,
            sourceLoopEndFrame);
        seekSourceToStartFrame(*lane.source, parkedFrame);
    }
}

void AudioEngine::requestOfflineMixdownCancel() {
    m_offlineMixdownCancelRequested.store(true, std::memory_order_release);
}

AudioEngine::OfflineMixdownStatus AudioEngine::renderOfflineMixdown(
    const int32_t sampleRate,
    const std::vector<std::string> &wavPaths,
    const std::vector<float> &gains,
    const int64_t startPositionMs,
    const int64_t sessionTimelineEndMs,
    const std::vector<int64_t> &laneClipStartMs,
    const std::vector<int64_t> &laneClipDurationMs,
    const std::vector<uint8_t> &laneLoopEnabled,
    const std::vector<int64_t> &laneLoopSourceStartMs,
    const std::vector<int64_t> &laneLoopSourceEndMs,
    const std::vector<float> &lanePan,
    const std::string &outputPath,
    const std::function<void(float)> &progressCallback) {
    __android_log_print(ANDROID_LOG_INFO, playback::kMixdownLogTag, "mixdown_start");
    m_offlineMixdownCancelRequested.store(false, std::memory_order_release);

    if (outputPath.empty() || wavPaths.empty() || wavPaths.size() != gains.size()) {
        __android_log_print(ANDROID_LOG_ERROR, playback::kMixdownLogTag, "mixdown_failed invalid_args");
        return OfflineMixdownStatus::Failed;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        playback::kMixdownLogTag,
        "mixdown_spec_lanes count=%zu startMs=%lld endMs=%lld",
        wavPaths.size(),
        static_cast<long long>(startPositionMs),
        static_cast<long long>(sessionTimelineEndMs));

    configureProject(sampleRate, 16);
    m_isPlaying.store(false, std::memory_order_release);
    stopIoThread();
    stopHotJoinThread();

    const int64_t startFrame = playbackStartFrameFromMs(startPositionMs, m_sampleRate);
    int64_t endFrame = playbackStartFrameFromMs(sessionTimelineEndMs, m_sampleRate);
    if (endFrame <= startFrame) {
        endFrame = startFrame + 1;
    }

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
                wavPaths,
                gains,
                startFrame,
                laneClipStartMs,
                laneClipDurationMs,
                laneLoopEnabled,
                laneLoopSourceStartMs,
                laneLoopSourceEndMs,
                lanePan)) {
            clearPlaybackLanesLocked();
            resetMasterPlaybackTimeline();
            __android_log_print(ANDROID_LOG_ERROR, playback::kMixdownLogTag, "mixdown_failed arm_lanes");
            return OfflineMixdownStatus::Failed;
        }
    }

    initializeMasterPlaybackTimeline(startFrame);
    m_playbackSessionEndFrame.store(endFrame, std::memory_order_release);
    m_sessionHasLoopLanes.store(false, std::memory_order_release);

    if (m_renderScratch.size() < playback::kRenderScratchFloatCount) {
        m_renderScratch.resize(playback::kRenderScratchFloatCount);
    }

    playback::StreamingPcm16WavWriter writer;
    if (!writer.Open(outputPath, m_sampleRate, 2)) {
        std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
        clearPlaybackLanesLocked();
        resetMasterPlaybackTimeline();
        __android_log_print(ANDROID_LOG_ERROR, playback::kMixdownLogTag, "mixdown_failed open_output");
        return OfflineMixdownStatus::Failed;
    }

    const int64_t totalFrames = endFrame - startFrame;
    std::vector<float> blockBuffer(
        static_cast<std::size_t>(playback::kOfflineMixdownBlockFrames) * 2u, 0.0f);
    int64_t renderedFrames = 0;

    while (renderedFrames < totalFrames) {
        if (m_offlineMixdownCancelRequested.load(std::memory_order_acquire)) {
            writer.Abort();
            std::remove(outputPath.c_str());
            std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
            clearPlaybackLanesLocked();
            resetMasterPlaybackTimeline();
            __android_log_print(ANDROID_LOG_WARN, playback::kMixdownLogTag, "mixdown_failed cancelled");
            return OfflineMixdownStatus::Cancelled;
        }

        const int32_t framesThisBlock = static_cast<int32_t>(std::min<int64_t>(
            playback::kOfflineMixdownBlockFrames,
            totalFrames - renderedFrames));
        const int64_t transportFrame = startFrame + renderedFrames;

        {
            std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
            syncLaneSourcesForOfflineTransport(transportFrame);
            renderBlock(
                blockBuffer.data(),
                framesThisBlock,
                2,
                transportFrame,
                RenderBlockInputMode::DirectSource,
                true,
                true);
        }

        if (!writer.WriteFloatInterleaved(
                blockBuffer.data(),
                static_cast<std::size_t>(framesThisBlock) * 2u)) {
            writer.Abort();
            std::remove(outputPath.c_str());
            std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
            clearPlaybackLanesLocked();
            resetMasterPlaybackTimeline();
            __android_log_print(ANDROID_LOG_ERROR, playback::kMixdownLogTag, "mixdown_failed write");
            return OfflineMixdownStatus::Failed;
        }

        renderedFrames += framesThisBlock;
        if (progressCallback && totalFrames > 0) {
            const float progress =
                static_cast<float>(renderedFrames) / static_cast<float>(totalFrames);
            progressCallback(std::min(progress, 1.0f));
            __android_log_print(
                ANDROID_LOG_DEBUG,
                playback::kMixdownLogTag,
                "mixdown_progress %.3f",
                progress);
        }
    }

    const uint32_t bytesWritten = writer.dataBytesWritten();
    if (!writer.Finalize()) {
        std::remove(outputPath.c_str());
        std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
        clearPlaybackLanesLocked();
        resetMasterPlaybackTimeline();
        __android_log_print(ANDROID_LOG_ERROR, playback::kMixdownLogTag, "mixdown_failed finalize");
        return OfflineMixdownStatus::Failed;
    }

    {
        std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
        clearPlaybackLanesLocked();
    }
    resetMasterPlaybackTimeline();
    if (progressCallback) {
        progressCallback(1.0f);
    }
    __android_log_print(ANDROID_LOG_INFO, playback::kMixdownLogTag, "mixdown_success bytes=%u",
                        bytesWritten);
    return OfflineMixdownStatus::Success;
}

void AudioEngine::render(float *outputInterleaved,
                         int32_t numFrames,
                         int32_t channels,
                         int32_t /*sampleRate*/) {
    if (!outputInterleaved || numFrames <= 0 || channels <= 0) {
        return;
    }

    if (!m_isPlaying.load(std::memory_order_acquire)) return;

    const int64_t transportFrameAtBlock =
        m_masterPlaybackFrame.load(std::memory_order_acquire);

    const std::size_t outSampleCount = static_cast<std::size_t>(numFrames) *
                                       static_cast<std::size_t>(channels);

    int32_t minFramesReturned = numFrames;
    const bool readAnyLane = renderBlock(
        outputInterleaved,
        numFrames,
        channels,
        transportFrameAtBlock,
        RenderBlockInputMode::RingBuffer,
        false,
        false,
        &minFramesReturned);

    if (m_isPlaying.load(std::memory_order_acquire)) {
        m_masterPlaybackFrame.fetch_add(static_cast<int64_t>(numFrames),
                                        std::memory_order_release);
    }

    if (readAnyLane) {
        const float blockPeak =
            playback::PeakAbsInterleaved(outputInterleaved, outSampleCount);
        float sessionPeak =
            m_masterPeakHoldLinear.load(std::memory_order_relaxed);
        if (blockPeak > sessionPeak) {
            m_masterPeakHoldLinear.store(blockPeak, std::memory_order_release);
        }
        if (playback::kMasterSafetySoftClipEnabled) {
            playback::ProcessMasterSafetySoftClip(outputInterleaved, outSampleCount);
        }

        renderMaybeCompletePlaybackMaster(numFrames, channels, minFramesReturned);
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
    staging.pan = 0.0f;
    staging.channels = 0;
    staging.clipStartMs = 0;
    staging.clipDurationMs = 0;
    staging.loopEnabled = false;
    staging.loopSourceStartMs = 0;
    staging.loopSourceEndMs = 0;
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
    staging.pan = item.pan;
    staging.channels = staging.source->channelCount();
    staging.clipStartMs = item.clipStartMs;
    staging.clipDurationMs = item.clipDurationMs;
    staging.loopEnabled = item.loopEnabled;
    staging.loopSourceStartMs = item.loopSourceStartMs;
    staging.loopSourceEndMs = item.loopSourceEndMs;

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

    const bool loopEnabled = staging.loopEnabled;
    const int64_t clipStartFrame =
        playbackStartFrameFromMs(staging.clipStartMs, m_sampleRate);
    const int64_t clipEndFrame =
        loopEnabled
            ? 0
            : clipTimelineEndFrameForLane(clipStartFrame, staging.clipDurationMs, m_sampleRate);
    const int64_t commitFrame = transportFrame();

    if (!loopEnabled && clipEndFrame > 0 && commitFrame >= clipEndFrame) {
        clearHotJoinStagingLocked(laneIndex);
        setLaneInactiveLocked(m_playbackLanes[laneIndex]);
        return false;
    }

    int64_t sourceLoopStartFrame =
        loopEnabled ? playbackStartFrameFromMs(staging.loopSourceStartMs, m_sampleRate) : 0;
    int64_t sourceLoopEndFrame =
        loopEnabled ? playbackStartFrameFromMs(staging.loopSourceEndMs, m_sampleRate) : 0;
    const int64_t sourceSeekFrame =
        laneSourceSeekFrameForArm(
            commitFrame,
            clipStartFrame,
            loopEnabled,
            sourceLoopStartFrame,
            sourceLoopEndFrame);
    if (!seekSourceToStartFrame(*staging.source, sourceSeekFrame)) {
        return false;
    }

    const bool beforeClipStart = !loopEnabled && commitFrame < clipStartFrame;
    const bool pastClipEnd =
        !loopEnabled && clipEndFrame > 0 && commitFrame >= clipEndFrame;
    bool exhaustedAtStart = false;
    if (pastClipEnd) {
        exhaustedAtStart = true;
    } else if (beforeClipStart) {
        exhaustedAtStart = false;
    } else if (loopEnabled) {
        exhaustedAtStart = sourceLoopEndFrame <= sourceLoopStartFrame;
    } else {
        exhaustedAtStart = isSourceExhaustedAtStart(*staging.source, sourceSeekFrame);
    }

    if (loopEnabled && staging.source) {
        const int64_t totalFrames = staging.source->totalFrames();
        sourceLoopEndFrame =
            clampLoopEndFrameToSource(sourceLoopEndFrame, totalFrames);
        if (totalFrames > 0 && sourceLoopStartFrame > totalFrames) {
            sourceLoopStartFrame = totalFrames;
        }
        if (!pastClipEnd && !beforeClipStart) {
            exhaustedAtStart = sourceLoopEndFrame <= sourceLoopStartFrame;
        }
    }

    staging.ring->reset();

    const bool activeOnTimeline =
        laneActiveOnTimelineForPlayback(
            commitFrame, clipStartFrame, clipEndFrame, loopEnabled);
    if (!exhaustedAtStart && !beforeClipStart && activeOnTimeline) {
        const int32_t prerollTargetFrames = computePrerollFramesForSampleRate(m_sampleRate);
        std::vector<float> preroll(static_cast<std::size_t>(prerollTargetFrames) *
                                   static_cast<std::size_t>(staging.channels));
        const int32_t prerollFrames =
            loopEnabled
                ? readLaneSourceFrames(
                      *staging.source,
                      preroll.data(),
                      prerollTargetFrames,
                      staging.channels,
                      true,
                      sourceLoopStartFrame,
                      sourceLoopEndFrame)
                : staging.source->readFrames(preroll.data(), prerollTargetFrames);
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
    const bool publishAudible =
        m_playbackLanes[laneIndex].hotJoinPublishAudible.load(std::memory_order_acquire);
    lane.audibleEnabled.store(publishAudible, std::memory_order_release);
    lane.gain.store(staging.gain, std::memory_order_release);
    lane.pan.store(std::clamp(staging.pan, -1.0f, 1.0f), std::memory_order_release);
    lane.sourceExhausted.store(exhaustedAtStart, std::memory_order_release);
    lane.srcChannels.store(staging.channels, std::memory_order_release);
    lane.clipTimelineStartFrame.store(clipStartFrame, std::memory_order_release);
    lane.clipTimelineEndFrame.store(clipEndFrame, std::memory_order_release);
    lane.loopEnabled.store(loopEnabled, std::memory_order_release);
    lane.sourceLoopStartFrame.store(sourceLoopStartFrame, std::memory_order_release);
    lane.sourceLoopEndFrame.store(sourceLoopEndFrame, std::memory_order_release);
    lane.lifecycle.store(
        exhaustedAtStart ? PlaybackLaneLifecycle::Exhausted : PlaybackLaneLifecycle::Active,
        std::memory_order_release);

    if (loopEnabled) {
        m_sessionHasLoopLanes.store(true, std::memory_order_release);
    }

    staging.source.reset();
    staging.path.clear();
    staging.gain = 1.0f;
    staging.pan = 0.0f;
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
                                      const int64_t clipDurationMs,
                                      const bool loopEnabled,
                                      const int64_t loopSourceStartMs,
                                      const int64_t loopSourceEndMs,
                                      const float pan) {
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
        item.pan = std::clamp(pan, -1.0f, 1.0f);
        item.clipStartMs = std::max<int64_t>(0, clipStartMs);
        item.clipDurationMs = std::max<int64_t>(0, clipDurationMs);
        item.loopEnabled = loopEnabled;
        item.loopSourceStartMs = std::max<int64_t>(0, loopSourceStartMs);
        item.loopSourceEndMs = std::max<int64_t>(0, loopSourceEndMs);
        const int64_t clipStartFrame =
            playbackStartFrameFromMs(item.clipStartMs, m_sampleRate);
        const int64_t clipEndFrame =
            loopEnabled
                ? 0
                : clipTimelineEndFrameForLane(
                      clipStartFrame, item.clipDurationMs, m_sampleRate);
        const int64_t sourceLoopStartFrame =
            loopEnabled ? playbackStartFrameFromMs(item.loopSourceStartMs, m_sampleRate) : 0;
        const int64_t sourceLoopEndFrame =
            loopEnabled ? playbackStartFrameFromMs(item.loopSourceEndMs, m_sampleRate) : 0;
        PlaybackLaneSlot &reservedSlot = m_playbackLanes[reservedLane];
        reservedSlot.gain.store(gain, std::memory_order_release);
        reservedSlot.pan.store(std::clamp(pan, -1.0f, 1.0f), std::memory_order_release);
        reservedSlot.clipTimelineStartFrame.store(clipStartFrame, std::memory_order_release);
        reservedSlot.clipTimelineEndFrame.store(clipEndFrame, std::memory_order_release);
        reservedSlot.loopEnabled.store(loopEnabled, std::memory_order_release);
        reservedSlot.sourceLoopStartFrame.store(sourceLoopStartFrame,
                                                std::memory_order_release);
        reservedSlot.sourceLoopEndFrame.store(sourceLoopEndFrame, std::memory_order_release);
        reservedSlot.hotJoinPublishAudible.store(true, std::memory_order_release);
        reservedSlot.lifecycle.store(PlaybackLaneLifecycle::Preparing,
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
