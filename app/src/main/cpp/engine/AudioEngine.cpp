#include "AudioEngine.h"

#include "AudioSyncLogConfig.h"
#include "OutputRenderAhead.h"
#include "OverdubStartupOptimization.h"

#include "DeviceLatencyBudgetDiagnostics.h"
#include "AudioStreamConfigurationDiagnostics.h"
#include "OboeTimestampDiagnostics.h"
#include "TransportClockDiagnostics.h"

#include <cassert>
#include <android/log.h>
#include <cstdarg>
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

int64_t laneSourceSeekFrame(int64_t transportStartFrame,
                            int64_t clipStartFrame,
                            int64_t sourceTrimStartFrame) {
    if (transportStartFrame <= clipStartFrame) return sourceTrimStartFrame;
    return (transportStartFrame - clipStartFrame) + sourceTrimStartFrame;
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
                                  int64_t loopSourceEndFrame,
                                  int64_t sourceTrimStartFrame) {
    if (!loopEnabled) {
        return laneSourceSeekFrame(
            transportStartFrame, clipStartFrame, sourceTrimStartFrame);
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
constexpr const char *kTransportFrameMapLogTag = "TransportFrameMap";

void logTransportFrameMap(const char *format, ...) {
    if (!audio_sync_log_config::transportFrameVerboseEnabled()) {
        return;
    }
    va_list args;
    va_start(args, format);
    __android_log_vprint(ANDROID_LOG_INFO, kTransportFrameMapLogTag, format, args);
    va_end(args);
}
constexpr const char *kAudioSyncDiagLogTag = "AudioSyncDiag";
constexpr float kFirstNonSilentPeakThreshold = 1.0e-7f;
constexpr float kFirstAudiblePeakThreshold = 0.005f;
constexpr int64_t kPlaybackMilestoneTransportUnset = -1;
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
    lane.sourceTrimStartFrame.store(0, std::memory_order_release);
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
                                           const int64_t sourceTrimStartMs,
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
    const int64_t sourceTrimStartFrame =
        loopEnabled ? 0 : playbackStartFrameFromMs(sourceTrimStartMs, m_sampleRate);
    const int64_t sourceSeekFrame =
        laneSourceSeekFrameForArm(
            transportStartFrame,
            clipStartFrame,
            loopEnabled,
            sourceLoopStartFrame,
            sourceLoopEndFrame,
            sourceTrimStartFrame);
    playback::logTransportFrameMap(
        "lane_seek_arm lane=%zu transportStartFrame=%lld clipStartFrame=%lld loop=%d "
        "loopSourceStartFrame=%lld loopSourceEndFrame=%lld sourceTrimStartFrame=%lld "
        "sourceSeekFrame=%lld",
        laneIndex,
        static_cast<long long>(transportStartFrame),
        static_cast<long long>(clipStartFrame),
        loopEnabled ? 1 : 0,
        static_cast<long long>(sourceLoopStartFrame),
        static_cast<long long>(sourceLoopEndFrame),
        static_cast<long long>(sourceTrimStartFrame),
        static_cast<long long>(sourceSeekFrame));
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
    lane.sourceTrimStartFrame.store(sourceTrimStartFrame, std::memory_order_release);

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
        char stage[96];
        std::snprintf(stage, sizeof(stage), "lane_decoder_open_begin lane=%zu", laneIndex);
        logPlaybackStartupMilestone(stage);
        auto source = std::make_shared<LocalWavSource>(wavPath);
        if (!source->open()) return false;
        std::snprintf(stage, sizeof(stage), "lane_decoder_open_done lane=%zu", laneIndex);
        logPlaybackStartupMilestone(stage);
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
            char stage[96];
            std::snprintf(
                stage,
                sizeof(stage),
                "lane_preroll_fill_done lane=%zu frames=%d",
                laneIndex,
                prerollFrames);
            logPlaybackStartupMilestone(stage);
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
                                         const std::vector<int64_t> &laneSourceTrimStartMs,
                                         const std::vector<float> &lanePan) {
    logPlaybackStartupMilestone("arm_lanes_begin");
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
        const int64_t sourceTrimStartMs =
            laneSourceTrimStartMs.empty() ? 0 : laneSourceTrimStartMs[0];
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
                sourceTrimStartMs,
                pan)) {
            clearPlaybackLanesLocked();
            return false;
        }
        m_renderScratch.resize(playback::kRenderScratchFloatCount);
        logPlaybackStartupMilestone("arm_lanes_done");
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
        const int64_t sourceTrimStartMs =
            laneIdx < laneSourceTrimStartMs.size() ? laneSourceTrimStartMs[laneIdx] : 0;
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
                sourceTrimStartMs,
                pan)) {
            clearPlaybackLanesLocked();
            return false;
        }
    }

    logPlaybackStartupMilestone("arm_lanes_done");
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

void AudioEngine::setSessionInputRouteKey(const std::string &routeKey) {
    m_sessionInputRouteKey = routeKey;
}

void AudioEngine::setSessionTransportLatenciesNs(const int64_t inputLatencyNs,
                                                 const int64_t outputLatencyNs) {
    m_sessionInputLatencyNs.store(std::max<int64_t>(0, inputLatencyNs),
                                  std::memory_order_release);
    m_sessionOutputLatencyNs.store(std::max<int64_t>(0, outputLatencyNs),
                                   std::memory_order_release);
    m_liveOutputLatencyValid.store(false, std::memory_order_release);
    m_outputRenderAheadLogged.store(false, std::memory_order_release);
}

void AudioEngine::refreshLiveOutputLatencyFromStream(oboe::AudioStream *const stream) {
    int64_t liveNs = m_liveOutputLatencyNs.load(std::memory_order_relaxed);
    bool liveValid = m_liveOutputLatencyValid.load(std::memory_order_relaxed);
    output_render_ahead::refreshLiveOutputLatencyFromStream(stream, liveNs, liveValid);
    m_liveOutputLatencyNs.store(liveNs, std::memory_order_release);
    m_liveOutputLatencyValid.store(liveValid, std::memory_order_release);
}

int64_t AudioEngine::effectiveOutputLatencyNs() const {
    return output_render_ahead::effectiveOutputLatencyNs(
        m_liveOutputLatencyNs.load(std::memory_order_acquire),
        m_liveOutputLatencyValid.load(std::memory_order_acquire),
        m_sessionOutputLatencyNs.load(std::memory_order_acquire));
}

int64_t AudioEngine::outputLatencyFrames() const {
    return output_render_ahead::latencyFramesFromNs(effectiveOutputLatencyNs(), m_sampleRate);
}

int64_t AudioEngine::mixTransportFrameAtMonotonicNs(const int64_t monotonicNs) const {
    const transport_clock::TransportClockAnchor anchor = transportClockAnchor();
    return output_render_ahead::mixTransportFrameAtCallback(
        monotonicNs,
        effectiveOutputLatencyNs(),
        anchor,
        m_masterPlaybackFrame.load(std::memory_order_acquire),
        m_sampleRate);
}

transport_clock::TransportClockAnchor AudioEngine::transportClockAnchor() const {
    transport_clock::TransportClockAnchor anchor;
    if (!m_transportClockAnchorValid.load(std::memory_order_acquire)) {
        return anchor;
    }
    anchor.transportStartFrame =
        m_anchorTransportStartFrame.load(std::memory_order_acquire);
    anchor.monotonicStartNs = m_anchorMonotonicStartNs.load(std::memory_order_acquire);
    anchor.sampleRateHz = m_anchorSampleRateHz.load(std::memory_order_acquire);
    return anchor;
}

void AudioEngine::installTransportClockAnchor(const int64_t transportStartFrame,
                                              const int64_t monotonicStartNs,
                                              const char *const reason) {
    if (monotonicStartNs <= 0 || m_sampleRate <= 0) {
        return;
    }
    m_anchorTransportStartFrame.store(transportStartFrame, std::memory_order_release);
    m_anchorMonotonicStartNs.store(monotonicStartNs, std::memory_order_release);
    m_anchorSampleRateHz.store(m_sampleRate, std::memory_order_release);
    m_transportClockAnchorValid.store(true, std::memory_order_release);
    transport_clock_diag::resetTransportClockDiagnostics();
    oboe_timestamp_diag::resetOutputTimestampDiagnostics();
    transport_clock_diag::logTransportClockAnchor(transportClockAnchor(), reason);
}

void AudioEngine::resetTransportClockAnchor() {
    m_transportClockAnchorValid.store(false, std::memory_order_release);
    m_anchorTransportStartFrame.store(0, std::memory_order_release);
    m_anchorMonotonicStartNs.store(0, std::memory_order_release);
    m_anchorSampleRateHz.store(0, std::memory_order_release);
    m_inputCaptureBufferIndex.store(0, std::memory_order_release);
}

void AudioEngine::finalizeSessionPerceivedPlaybackOffsetMs() {
    m_sessionPerceivedPlaybackOffsetMs.store(-1, std::memory_order_release);

    const int64_t armNs = m_overdubPlaybackArmSteadyNs.load(std::memory_order_acquire);
    const int64_t firstInputNs = m_overdubFirstInputSteadyNs.load(std::memory_order_acquire);
    if (armNs <= 0 || firstInputNs <= armNs) {
        return;
    }

    const int64_t armToFirstInputMs = (firstInputNs - armNs) / 1'000'000LL;
    const int64_t inputCaptureMs =
        m_sessionInputLatencyNs.load(std::memory_order_acquire) / 1'000'000LL;

    const int64_t liveOutputNs = m_liveOutputLatencyNs.load(std::memory_order_acquire);
    const bool liveOutputValid = m_liveOutputLatencyValid.load(std::memory_order_acquire);
    if (!liveOutputValid || liveOutputNs <= 0) {
        return;
    }
    const int64_t liveOutputMs = liveOutputNs / 1'000'000LL;

    const int64_t offsetMs = liveOutputMs - inputCaptureMs;
    m_sessionPerceivedPlaybackOffsetMs.store(offsetMs, std::memory_order_release);

    const int64_t capturePlacementMs = recordingFirstSampleTransportPositionMs();
    const int64_t playbackArmMs =
        m_sampleRate > 0
            ? (m_playbackArmTransportStartFrame.load(std::memory_order_acquire) * 1000LL) /
                  static_cast<int64_t>(m_sampleRate)
            : 0LL;

    __android_log_print(
        ANDROID_LOG_INFO,
        playback::kAudioSyncDiagLogTag,
        "[SESSION_PERCEIVED_PLAYBACK_OFFSET] offsetMs=%lld "
        "armToFirstInputMs=%lld liveOutputMs=%lld inputCaptureMs=%lld "
        "capturePlacementMs=%lld playbackArmMs=%lld",
        static_cast<long long>(offsetMs),
        static_cast<long long>(armToFirstInputMs),
        static_cast<long long>(liveOutputMs),
        static_cast<long long>(inputCaptureMs),
        static_cast<long long>(capturePlacementMs),
        static_cast<long long>(playbackArmMs));
}

int64_t AudioEngine::transportFrameAtMonotonicNs(const int64_t monotonicNs) const {
    const transport_clock::TransportClockAnchor anchor = transportClockAnchor();
    if (anchor.isValid()) {
        return anchor.transportFrameAt(monotonicNs);
    }
    return m_masterPlaybackFrame.load(std::memory_order_acquire);
}

int64_t AudioEngine::estimatedCaptureMonotonicNs(
        const int64_t appReceiveMonotonicNs) const {
    const int64_t inputLatencyNs =
        m_sessionInputLatencyNs.load(std::memory_order_acquire);
    return inputLatencyNs > 0 ? appReceiveMonotonicNs - inputLatencyNs : appReceiveMonotonicNs;
}

int64_t AudioEngine::estimatedCaptureTransportFrame(
        const int64_t appReceiveMonotonicNs) const {
    const transport_clock::TransportClockAnchor anchor = transportClockAnchor();
    if (!anchor.isValid()) {
        return m_masterPlaybackFrame.load(std::memory_order_acquire);
    }
    return anchor.transportFrameAt(estimatedCaptureMonotonicNs(appReceiveMonotonicNs));
}

int64_t AudioEngine::currentTransportFrame() const {
    return transportFrameAtMonotonicNs(transport_clock::monotonicNowNs());
}

int64_t AudioEngine::transportStartFrame() const {
    const transport_clock::TransportClockAnchor anchor = transportClockAnchor();
    if (anchor.isValid()) {
        return anchor.transportStartFrame;
    }
    return m_masterPlaybackStartFrame.load(std::memory_order_acquire);
}

int64_t AudioEngine::transportFrame() const {
    return currentTransportFrame();
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------

bool AudioEngine::openInputStream(int32_t channelCount) {
    logPlaybackStartupMilestone("open_input_stream_begin");
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setSampleRate(m_sampleRate);
    builder.setChannelCount(channelCount);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result openResult = builder.openStream(stream);
    audio_stream_config_diag::StreamOpenRequest request{};
    request.audioApi = oboe::AudioApi::Unspecified;
    request.performanceMode = oboe::PerformanceMode::LowLatency;
    request.sharingMode = oboe::SharingMode::Shared;
    request.sampleRateHz = m_sampleRate;
    request.channelCount = channelCount;
    audio_stream_config_diag::logOpenedStream(
        "input",
        request,
        stream ? stream.get() : nullptr,
        openResult);
    if (openResult != oboe::Result::OK || !stream) return false;
    if (stream->requestStart() != oboe::Result::OK) {
        stream->close();
        return false;
    }
    m_inputStream = stream;
    oboe_timestamp_diag::resetInputTimestampDiagnostics();
    logPlaybackStartupMilestone("open_input_stream_done");
    return true;
}

void AudioEngine::closeInputStream() {
    if (!m_inputStream) return;
    m_inputStream->close();
    m_inputStream.reset();
}

int64_t AudioEngine::steadyClockNowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

void AudioEngine::setOutputStreamForDiagnostics(
    std::shared_ptr<oboe::AudioStream> stream) {
    m_outputStreamForDiagnostics = std::move(stream);
    if (!m_outputStreamForDiagnostics) {
        m_liveOutputLatencyNs.store(0, std::memory_order_release);
        m_liveOutputLatencyValid.store(false, std::memory_order_release);
        m_outputRenderAheadLogged.store(false, std::memory_order_release);
    }
    OboeStreamSnapshot snapshot;
    if (m_outputStreamForDiagnostics) {
        snapshot.sampleRateHz = m_outputStreamForDiagnostics->getSampleRate();
        snapshot.channelCount = m_outputStreamForDiagnostics->getChannelCount();
        snapshot.framesPerBurst = m_outputStreamForDiagnostics->getFramesPerBurst();
        snapshot.bufferCapacityInFrames =
            m_outputStreamForDiagnostics->getBufferCapacityInFrames();
        snapshot.bufferSizeInFrames =
            m_outputStreamForDiagnostics->getBufferSizeInFrames();
        snapshot.performanceMode =
            static_cast<int32_t>(m_outputStreamForDiagnostics->getPerformanceMode());
        snapshot.sharingMode =
            static_cast<int32_t>(m_outputStreamForDiagnostics->getSharingMode());
        snapshot.audioSessionId =
            static_cast<int32_t>(m_outputStreamForDiagnostics->getSessionId());
    }
    m_outputStreamSnapshot = snapshot;
}

void AudioEngine::captureInputStreamSnapshot() {
    OboeStreamSnapshot snapshot;
    if (m_inputStream) {
        snapshot.sampleRateHz = m_inputStream->getSampleRate();
        snapshot.channelCount = m_inputStream->getChannelCount();
        snapshot.framesPerBurst = m_inputStream->getFramesPerBurst();
        snapshot.bufferCapacityInFrames = m_inputStream->getBufferCapacityInFrames();
        snapshot.bufferSizeInFrames = m_inputStream->getBufferSizeInFrames();
        snapshot.performanceMode = static_cast<int32_t>(m_inputStream->getPerformanceMode());
        snapshot.sharingMode = static_cast<int32_t>(m_inputStream->getSharingMode());
        snapshot.audioSessionId = static_cast<int32_t>(m_inputStream->getSessionId());
    }
    m_inputStreamSnapshot = snapshot;
}

void AudioEngine::resetPlaybackStartupBreakdownFlags() {
    m_startupIoPrefetchLogged.store(false, std::memory_order_release);
    m_startupFirstRenderLogged.store(false, std::memory_order_release);
    m_startupFirstOboeCallbackLogged.store(false, std::memory_order_release);
    m_openInputBeginSteadyNs.store(0, std::memory_order_release);
    m_openInputDoneSteadyNs.store(0, std::memory_order_release);
    m_oboeStreamOpenBeginSteadyNs.store(0, std::memory_order_release);
    m_oboeStreamOpenDoneSteadyNs.store(0, std::memory_order_release);
    m_oboeStreamStartDoneSteadyNs.store(0, std::memory_order_release);
    m_firstOboeCallbackSteadyNs.store(0, std::memory_order_release);
    m_overdubJniReadySteadyNs.store(0, std::memory_order_release);
}

AudioEngine::SoftwareBufferProfile AudioEngine::softwareBufferProfile() const {
    SoftwareBufferProfile profile;
    profile.ringDurationSeconds = playback::kRingDurationSeconds;
    profile.prerollWallMs = playback::kPrerollWallMs;
    profile.ioBatchFrames = playback::kIoBatchFrames;
    profile.inputReadFrames =
        m_isRecording.load(std::memory_order_acquire) && m_sessionRecordReadFrames > 0
            ? m_sessionRecordReadFrames
            : kInputReadBlockFrames;
    profile.ioIdleSleepMs = playback::kIoIdleSleepMs;
    profile.inputReadTimeoutMs =
        static_cast<int32_t>(kReadTimeoutNanos / oboe::kNanosPerMillisecond);
    return profile;
}

void AudioEngine::recordOutputCallbackCost(const int32_t callbackFrames,
                                           const int64_t callbackDurationUs,
                                           const int64_t renderDurationUs) {
    if (callbackFrames > 0) {
        m_lastOutputCallbackFrames.store(callbackFrames, std::memory_order_release);
    }
    m_outputCallbackDurationUs.record(callbackDurationUs);
    m_outputRenderDurationUs.record(renderDurationUs);
}

void AudioEngine::recordInputLoopCost(const int32_t readFrames,
                                      const int64_t readBlockingDurationUs,
                                      const int64_t processingDurationUs) {
    if (readFrames > 0) {
        m_lastInputReadFrames.store(readFrames, std::memory_order_release);
    }
    m_inputReadBlockingDurationUs.record(readBlockingDurationUs);
    m_inputProcessingDurationUs.record(processingDurationUs);
}

AudioEngine::CallbackCostSnapshot AudioEngine::outputCallbackCostSnapshot() const {
    CallbackCostSnapshot snapshot;
    snapshot.callbackFrames = m_lastOutputCallbackFrames.load(std::memory_order_acquire);
    const auto callbackSummary = m_outputCallbackDurationUs.snapshot();
    const auto renderSummary = m_outputRenderDurationUs.snapshot();
    snapshot.sampleCount = callbackSummary.sampleCount;
    snapshot.callbackMinUs = callbackSummary.minUs;
    snapshot.callbackAvgUs = callbackSummary.avgUs;
    snapshot.callbackMaxUs = callbackSummary.maxUs;
    snapshot.callbackP95Us = callbackSummary.p95Us;
    snapshot.renderMinUs = renderSummary.minUs;
    snapshot.renderAvgUs = renderSummary.avgUs;
    snapshot.renderMaxUs = renderSummary.maxUs;
    snapshot.renderP95Us = renderSummary.p95Us;
    if (m_outputStreamForDiagnostics) {
        const auto xRunResult = m_outputStreamForDiagnostics->getXRunCount();
        if (xRunResult) {
            snapshot.xRunCount = xRunResult.value();
        }
    }
    return snapshot;
}

AudioEngine::InputLoopCostSnapshot AudioEngine::inputLoopCostSnapshot() const {
    InputLoopCostSnapshot snapshot;
    snapshot.readFrames = m_lastInputReadFrames.load(std::memory_order_acquire);
    const auto readSummary = m_inputReadBlockingDurationUs.snapshot();
    const auto processingSummary = m_inputProcessingDurationUs.snapshot();
    snapshot.sampleCount = readSummary.sampleCount;
    snapshot.readBlockingMinUs = readSummary.minUs;
    snapshot.readBlockingAvgUs = readSummary.avgUs;
    snapshot.readBlockingMaxUs = readSummary.maxUs;
    snapshot.readBlockingP95Us = readSummary.p95Us;
    snapshot.processingMinUs = processingSummary.minUs;
    snapshot.processingAvgUs = processingSummary.avgUs;
    snapshot.processingMaxUs = processingSummary.maxUs;
    snapshot.processingP95Us = processingSummary.p95Us;
    return snapshot;
}

void AudioEngine::captureStartupSteadyNsForStage(const char *const stage,
                                                 const int64_t steadyNs) {
    if (!stage || steadyNs <= 0) {
        return;
    }
    if (std::strcmp(stage, "open_input_stream_begin") == 0) {
        m_openInputBeginSteadyNs.store(steadyNs, std::memory_order_release);
        return;
    }
    if (std::strcmp(stage, "open_input_stream_done") == 0) {
        m_openInputDoneSteadyNs.store(steadyNs, std::memory_order_release);
        return;
    }
    if (std::strcmp(stage, "oboe_stream_open_begin") == 0) {
        m_oboeStreamOpenBeginSteadyNs.store(steadyNs, std::memory_order_release);
        return;
    }
    if (std::strcmp(stage, "oboe_stream_open_done") == 0) {
        m_oboeStreamOpenDoneSteadyNs.store(steadyNs, std::memory_order_release);
        return;
    }
    if (std::strcmp(stage, "oboe_stream_request_start_done") == 0) {
        m_oboeStreamStartDoneSteadyNs.store(steadyNs, std::memory_order_release);
        return;
    }
    if (std::strcmp(stage, "first_oboe_callback") == 0) {
        m_firstOboeCallbackSteadyNs.store(steadyNs, std::memory_order_release);
    }
}

bool AudioEngine::logFirstOboeCallbackOnce() {
    bool expected = false;
    if (!m_startupFirstOboeCallbackLogged.compare_exchange_strong(
            expected,
            true,
            std::memory_order_release)) {
        return false;
    }
    logPlaybackStartupMilestone("first_oboe_callback");
    return true;
}

void AudioEngine::logPlaybackStartupMilestone(const char *const stage) {
    if (!stage || stage[0] == '\0') {
        return;
    }
    if (audio_sync_log_config::clockValidationMode() &&
        !audio_sync_log_config::detailedStartupLogsEnabled()) {
        return;
    }
    const int64_t nowNs = steadyClockNowNs();
    captureStartupSteadyNsForStage(stage, nowNs);
    const int64_t anchorNs =
        m_overdubPlaybackArmSteadyNs.load(std::memory_order_acquire);
    if (anchorNs <= 0) {
        __android_log_print(
            ANDROID_LOG_INFO,
            playback::kAudioSyncDiagLogTag,
            "[PLAYBACK_STARTUP_BREAKDOWN] stage=%s preArm=1 steadyNs=%lld",
            stage,
            static_cast<long long>(nowNs));
        return;
    }

    const int64_t prevNs =
        m_startupLastMilestoneNs.load(std::memory_order_acquire);
    const int64_t cumulativeMs = (nowNs - anchorNs) / 1'000'000LL;
    const int64_t deltaMs =
        prevNs > 0 ? (nowNs - prevNs) / 1'000'000LL : cumulativeMs;
    m_startupLastMilestoneNs.store(nowNs, std::memory_order_release);
    __android_log_print(
        ANDROID_LOG_INFO,
        playback::kAudioSyncDiagLogTag,
        "[PLAYBACK_STARTUP_BREAKDOWN] stage=%s cumulativeMs=%lld deltaMs=%lld "
        "anchorNs=%lld steadyNs=%lld",
        stage,
        static_cast<long long>(cumulativeMs),
        static_cast<long long>(deltaMs),
        static_cast<long long>(anchorNs),
        static_cast<long long>(nowNs));
}

AudioEngine::PlaybackSessionTimings AudioEngine::playbackSessionTimings() const {
    PlaybackSessionTimings timings;
    timings.playbackArmSteadyNs =
        m_overdubPlaybackArmSteadyNs.load(std::memory_order_acquire);
    timings.firstInputSampleSteadyNs =
        m_overdubFirstInputSteadyNs.load(std::memory_order_acquire);
    timings.firstNonSilentOutputSteadyNs =
        m_overdubFirstNonSilentOutputSteadyNs.load(std::memory_order_acquire);
    timings.firstAudibleOutputSteadyNs =
        m_overdubFirstAudibleOutputSteadyNs.load(std::memory_order_acquire);
    timings.prerollFrames = computePrerollFramesForSampleRate(m_sampleRate);
    timings.ioBatchFrames = playback::kIoBatchFrames;
    timings.recordReadFrames =
        m_sessionRecordReadFrames > 0 ? m_sessionRecordReadFrames : kFramesPerRead;
    timings.playbackArmTransportStartFrame =
        m_playbackArmTransportStartFrame.load(std::memory_order_acquire);
    timings.firstNonSilentTransportFrame =
        m_firstNonSilentTransportFrame.load(std::memory_order_acquire);
    timings.firstAudiblePeakTransportFrame =
        m_firstAudiblePeakTransportFrame.load(std::memory_order_acquire);
    timings.firstAudiblePeakMicro =
        m_firstAudiblePeakMicro.load(std::memory_order_acquire);
    timings.openInputBeginSteadyNs =
        m_openInputBeginSteadyNs.load(std::memory_order_acquire);
    timings.openInputDoneSteadyNs =
        m_openInputDoneSteadyNs.load(std::memory_order_acquire);
    timings.oboeStreamOpenBeginSteadyNs =
        m_oboeStreamOpenBeginSteadyNs.load(std::memory_order_acquire);
    timings.oboeStreamOpenDoneSteadyNs =
        m_oboeStreamOpenDoneSteadyNs.load(std::memory_order_acquire);
    timings.oboeStreamStartDoneSteadyNs =
        m_oboeStreamStartDoneSteadyNs.load(std::memory_order_acquire);
    timings.firstOboeCallbackSteadyNs =
        m_firstOboeCallbackSteadyNs.load(std::memory_order_acquire);
    return timings;
}

void AudioEngine::capturePlaybackOutputMilestones(const int64_t transportFrameAtBlock,
                                                  const float *const outputInterleaved,
                                                  const int32_t numFrames,
                                                  const int32_t channels) {
    if (!outputInterleaved || numFrames <= 0 || channels <= 0) {
        return;
    }

    int32_t firstNonSilentFrameInBlock = -1;
    int32_t firstAudibleFrameInBlock = -1;
    float audiblePeak = 0.0f;

    for (int32_t frame = 0; frame < numFrames; ++frame) {
        float framePeak = 0.0f;
        for (int32_t channel = 0; channel < channels; ++channel) {
            const float sample =
                outputInterleaved[static_cast<std::size_t>(frame) *
                                      static_cast<std::size_t>(channels) +
                                  static_cast<std::size_t>(channel)];
            const float magnitude = sample < 0.0f ? -sample : sample;
            if (magnitude > framePeak) {
                framePeak = magnitude;
            }
        }
        if (firstNonSilentFrameInBlock < 0 &&
            framePeak > playback::kFirstNonSilentPeakThreshold) {
            firstNonSilentFrameInBlock = frame;
        }
        if (firstAudibleFrameInBlock < 0 &&
            framePeak > playback::kFirstAudiblePeakThreshold) {
            firstAudibleFrameInBlock = frame;
            audiblePeak = framePeak;
        }
    }

    const int64_t steadyNs = steadyClockNowNs();

    if (firstNonSilentFrameInBlock >= 0) {
        int64_t expectedNs = 0;
        if (m_overdubFirstNonSilentOutputSteadyNs.compare_exchange_strong(
                expectedNs,
                steadyNs,
                std::memory_order_release)) {
            m_firstNonSilentTransportFrame.store(
                transportFrameAtBlock + static_cast<int64_t>(firstNonSilentFrameInBlock),
                std::memory_order_release);
            logPlaybackStartupMilestone("first_non_silent_callback");
        }
    }

    if (firstAudibleFrameInBlock >= 0) {
        int64_t expectedNs = 0;
        if (m_overdubFirstAudibleOutputSteadyNs.compare_exchange_strong(
                expectedNs,
                steadyNs,
                std::memory_order_release)) {
            logPlaybackStartupMilestone("first_audible_callback");
            const int64_t audibleTransportFrame =
                transportFrameAtBlock + static_cast<int64_t>(firstAudibleFrameInBlock);
            m_firstAudiblePeakTransportFrame.store(audibleTransportFrame,
                                                   std::memory_order_release);
            m_firstAudiblePeakMicro.store(
                static_cast<int64_t>(audiblePeak * 1'000'000.0f),
                std::memory_order_release);

            const int64_t armSteadyNs =
                m_overdubPlaybackArmSteadyNs.load(std::memory_order_acquire);
            const int64_t armTransportStartFrame =
                m_playbackArmTransportStartFrame.load(std::memory_order_acquire);
            if (armSteadyNs > 0) {
                const int64_t armToFirstAudibleMs = (steadyNs - armSteadyNs) / 1'000'000LL;
                const int64_t transportStartMs =
                    m_sampleRate > 0
                        ? (armTransportStartFrame * 1000LL) /
                              static_cast<int64_t>(m_sampleRate)
                        : 0LL;
                const int64_t firstAudibleTransportMs =
                    m_sampleRate > 0
                        ? (audibleTransportFrame * 1000LL) /
                              static_cast<int64_t>(m_sampleRate)
                        : 0LL;
                if (audio_sync_log_config::shouldLogPlaybackLatency()) {
                    __android_log_print(
                        ANDROID_LOG_INFO,
                        playback::kAudioSyncDiagLogTag,
                        "[PLAYBACK_LATENCY] first_audible armToFirstAudibleMs=%lld "
                        "transportStartMs=%lld firstAudibleTransportMs=%lld peak=%f",
                        static_cast<long long>(armToFirstAudibleMs),
                        static_cast<long long>(transportStartMs),
                        static_cast<long long>(firstAudibleTransportMs),
                        audiblePeak);
                }
                logPlaybackStartupMilestone("startup_complete");
            }
        }
    }
}

bool AudioEngine::startRecording(int32_t channelCount,
                                 const std::string &outputPath,
                                 const int64_t startPositionMs) {
    logPlaybackStartupMilestone("start_recording_begin");
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
    m_recordedCaptureFrameCount.store(0, std::memory_order_release);
    m_inputCaptureBufferIndex.store(0, std::memory_order_release);

    if (!openInputStream(m_recordingChannelCount)) {
        m_isRecording = false;
        m_recordingInputLevel.store(0.0f, std::memory_order_release);
        return false;
    }
    configureInputReadSizeForSession();
    captureInputStreamSnapshot();

    // Clock.3: seed transport from timeline when recording-only; overdub shares playback transport.
    const bool playbackAlreadyActive = m_isPlaying.load(std::memory_order_acquire);
    if (!playbackAlreadyActive) {
        const int64_t startFrame = playbackStartFrameFromMs(startPositionMs, m_sampleRate);
        initializeMasterPlaybackTimeline(startFrame);
        installTransportClockAnchor(
            startFrame,
            transport_clock::monotonicNowNs(),
            "recording");
        playback::logTransportFrameMap(
            "recording_only_transport_seed startFrame=%lld startMs=%lld",
            static_cast<long long>(startFrame),
            static_cast<long long>(startPositionMs));
    }

    m_recordThread = std::thread(&AudioEngine::recordLoop, this);
    logPlaybackStartupMilestone("start_recording_done");
    return true;
}

bool AudioEngine::armOverdubPlaybackSession(
    const std::vector<std::string> &wavPaths,
    const std::vector<float> &gains,
    const int64_t startPositionMs,
    const int64_t sessionTimelineEndMs,
    const std::vector<int64_t> &laneClipStartMs,
    const std::vector<int64_t> &laneClipDurationMs,
    const std::vector<uint8_t> &laneLoopEnabled,
    const std::vector<int64_t> &laneLoopSourceStartMs,
    const std::vector<int64_t> &laneLoopSourceEndMs,
    const std::vector<int64_t> &laneSourceTrimStartMs,
    const std::vector<float> &lanePan) {
    if (!setPlaybackSources(
            wavPaths,
            gains,
            startPositionMs,
            sessionTimelineEndMs,
            laneClipStartMs,
            laneClipDurationMs,
            laneLoopEnabled,
            laneLoopSourceStartMs,
            laneLoopSourceEndMs,
            laneSourceTrimStartMs,
            lanePan,
            true,
            false)) {
        return false;
    }
    playback::logTransportFrameMap(
        "overdub_session_armed startMs=%lld",
        static_cast<long long>(startPositionMs));
    logPlaybackStartupMilestone("overdub_session_armed_only");
    return true;
}

void AudioEngine::markOverdubJniReady() {
    m_overdubJniReadySteadyNs.store(steadyClockNowNs(), std::memory_order_release);
    logPlaybackStartupMilestone("overdub_jni_ready");
}

bool AudioEngine::startOverdubRecordingSession(
    const std::vector<std::string> &wavPaths,
    const std::vector<float> &gains,
    const int64_t startPositionMs,
    const int64_t sessionTimelineEndMs,
    const std::vector<int64_t> &laneClipStartMs,
    const std::vector<int64_t> &laneClipDurationMs,
    const std::vector<uint8_t> &laneLoopEnabled,
    const std::vector<int64_t> &laneLoopSourceStartMs,
    const std::vector<int64_t> &laneLoopSourceEndMs,
    const std::vector<int64_t> &laneSourceTrimStartMs,
    const std::vector<float> &lanePan,
    const int32_t channelCount,
    const std::string &recordingOutputPath) {
    if (recordingOutputPath.empty()) {
        return false;
    }
    if (!armOverdubPlaybackSession(
            wavPaths,
            gains,
            startPositionMs,
            sessionTimelineEndMs,
            laneClipStartMs,
            laneClipDurationMs,
            laneLoopEnabled,
            laneLoopSourceStartMs,
            laneLoopSourceEndMs,
            laneSourceTrimStartMs,
            lanePan)) {
        return false;
    }
    logPlaybackStartupMilestone("overdub_session_before_start_recording");
    const bool recordingStarted = startRecording(channelCount, recordingOutputPath, 0L);
    if (recordingStarted) {
        logPlaybackStartupMilestone("overdub_session_after_start_recording");
    }
    return recordingStarted;
}

void AudioEngine::onRecordingFramesCaptured(const int32_t framesRead,
                                            const int64_t appReceiveMonotonicNs) {
    if (framesRead <= 0) {
        return;
    }

    m_recordedCaptureFrameCount.fetch_add(static_cast<int64_t>(framesRead),
                                          std::memory_order_release);

    int64_t expectedUnset = kRecordingFirstSampleTransportUnset;
    const int64_t placementFrame =
        estimatedCaptureTransportFrame(appReceiveMonotonicNs);
    if (!m_recordingFirstSampleTransportFrame.compare_exchange_strong(
            expectedUnset,
            placementFrame,
            std::memory_order_release)) {
        return;
    }

    const int64_t firstInputSteadyNs = steadyClockNowNs();
    m_overdubFirstInputSteadyNs.store(firstInputSteadyNs, std::memory_order_release);

    const int64_t armSteadyNs =
        m_overdubPlaybackArmSteadyNs.load(std::memory_order_acquire);
    if (armSteadyNs > 0) {
        const int64_t armToFirstInputMs = (firstInputSteadyNs - armSteadyNs) / 1'000'000LL;
        __android_log_print(
            ANDROID_LOG_INFO,
            playback::kAudioSyncDiagLogTag,
            "[LATENCY_BUDGET] timing arm_to_first_input_ms=%lld",
            static_cast<long long>(armToFirstInputMs));
    }

    const int64_t placementMs =
        m_sampleRate > 0 ? (placementFrame * 1000LL) / static_cast<int64_t>(m_sampleRate)
                         : 0LL;
    const char *placementMode =
        m_overdubPlaybackArmSteadyNs.load(std::memory_order_acquire) > 0 ? "overdub"
                                                                          : "recording";
    transport_clock_diag::logPlacementClockComparison(
        this,
        placementMode,
        placementFrame,
        appReceiveMonotonicNs);
    device_latency_budget_diag::logDeviceLatencyBudget(
        this,
        placementFrame,
        appReceiveMonotonicNs);
    playback::logTransportFrameMap(
        "recording_first_sample transportFrame=%lld transportMs=%lld capturedFrames=%lld",
        static_cast<long long>(placementFrame),
        static_cast<long long>(placementMs),
        static_cast<long long>(m_recordedCaptureFrameCount.load(std::memory_order_acquire)));
}

void AudioEngine::configureInputReadSizeForSession() {
    m_sessionRecordReadFrames = kFramesPerRead;
}

void AudioEngine::recordLoop() {
    const int32_t channelCount = std::max(1, m_recordingChannelCount);
    const int32_t readFrames =
        m_sessionRecordReadFrames > 0 ? m_sessionRecordReadFrames : kFramesPerRead;
    std::vector<float> buffer(static_cast<size_t>(readFrames * channelCount));

    while (m_isRecording) {
        if (!m_inputStream) break;
        const int64_t readStartNs = steadyClockNowNs();
        const auto result = m_inputStream->read(buffer.data(), readFrames, kReadTimeoutNanos);
        const int64_t readEndNs = steadyClockNowNs();
        if (!result) {
            if (result.error() != oboe::Result::ErrorTimeout) {
                break;
            }
            continue;
        }

        const int32_t framesRead = result.value();
        if (framesRead <= 0) continue;

        const int64_t appReceiveNs = transport_clock::monotonicNowNs();
        const int64_t bufferIndex =
            m_inputCaptureBufferIndex.fetch_add(1, std::memory_order_relaxed);
        transport_clock_diag::maybeLogInputCaptureCorrelation(
            this,
            bufferIndex,
            appReceiveNs,
            framesRead);

        oboe_timestamp_diag::maybeLogInputTimestamp(
            m_inputStream.get(),
            transportFrameAtMonotonicNs(appReceiveNs),
            framesRead);

        onRecordingFramesCaptured(framesRead, appReceiveNs);

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

        const int64_t processEndNs = steadyClockNowNs();
        recordInputLoopCost(
            readFrames,
            (readEndNs - readStartNs) / 1000LL,
            (processEndNs - readEndNs) / 1000LL);
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

    const int64_t capturedFrames = m_recordedCaptureFrameCount.load(std::memory_order_acquire);
    const int64_t capturedDurationMs = recordingCapturedDurationMs();
    const int64_t firstSampleMs = recordingFirstSampleTransportPositionMs();
    finalizeSessionPerceivedPlaybackOffsetMs();
    playback::logTransportFrameMap(
        "recording_stop capturedFrames=%lld capturedDurationMs=%lld firstSampleTransportMs=%lld wavSamples=%zu",
        static_cast<long long>(capturedFrames),
        static_cast<long long>(capturedDurationMs),
        static_cast<long long>(firstSampleMs),
        recordedSamples.size());

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
    const int64_t frame = currentTransportFrame();
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

int64_t AudioEngine::recordingCapturedDurationMs() const {
    const int64_t frames = m_recordedCaptureFrameCount.load(std::memory_order_acquire);
    const int32_t rate = m_sampleRate;
    if (frames <= 0 || rate <= 0) return 0L;
    return (frames * 1000L) / static_cast<int64_t>(rate);
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
    resetTransportClockAnchor();
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
                                     const std::vector<int64_t> &laneSourceTrimStartMs,
                                     const std::vector<float> &lanePan,
                                     const bool overdubInitialArm,
                                     const bool preserveActiveOverdubCapture) {
    const int64_t startFrame = playbackStartFrameFromMs(startPositionMs, m_sampleRate);
    int64_t endFrame = playbackStartFrameFromMs(sessionTimelineEndMs, m_sampleRate);
    if (endFrame > 0 && endFrame < startFrame) {
        endFrame = startFrame;
    }
    m_playbackSessionEndFrame.store(endFrame, std::memory_order_release);
    m_isPlaying.store(false, std::memory_order_release);
    const int64_t anchorNs = transport_clock::monotonicNowNs();
    const bool preserveCapture =
        preserveActiveOverdubCapture &&
        m_isRecording.load(std::memory_order_acquire);
    m_overdubPlaybackArmSteadyNs.store(steadyClockNowNs(), std::memory_order_release);
    m_sessionPerceivedPlaybackOffsetMs.store(-1, std::memory_order_release);
    if (!preserveCapture) {
        m_overdubFirstInputSteadyNs.store(0, std::memory_order_release);
    }
    m_overdubFirstNonSilentOutputSteadyNs.store(0, std::memory_order_release);
    m_overdubFirstAudibleOutputSteadyNs.store(0, std::memory_order_release);
    m_playbackArmTransportStartFrame.store(startFrame, std::memory_order_release);
    m_firstNonSilentTransportFrame.store(playback::kPlaybackMilestoneTransportUnset,
                                           std::memory_order_release);
    m_firstAudiblePeakTransportFrame.store(playback::kPlaybackMilestoneTransportUnset,
                                           std::memory_order_release);
    m_firstAudiblePeakMicro.store(0, std::memory_order_release);
    resetPlaybackStartupBreakdownFlags();
    m_startupLastMilestoneNs.store(steadyClockNowNs(), std::memory_order_release);
    logPlaybackStartupMilestone("playback_arm");

    const char *anchorReason =
        preserveCapture ? "overdub_rearm"
            : overdubInitialArm ? "overdub"
                                : "playback";
    installTransportClockAnchor(startFrame, anchorNs, anchorReason);

    logPlaybackStartupMilestone("stop_io_thread_begin");
    stopIoThread();
    logPlaybackStartupMilestone("stop_io_thread_done");

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
                laneSourceTrimStartMs,
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
    m_isPlaying.store(true, std::memory_order_release);
    logPlaybackStartupMilestone("playback_ungated");
    ensureHotJoinThreadRunning();
    logPlaybackStartupMilestone("ensure_hot_join_thread_done");
    logPlaybackStartupMilestone("set_playback_sources_done");
    m_masterPeakHoldLinear.store(0.0f, std::memory_order_release);
    playback::logTransportFrameMap(
        "playback_arm startFrame=%lld startMs=%lld laneCount=%zu",
        static_cast<long long>(startFrame),
        static_cast<long long>(startPositionMs),
        wavPaths.size());
    return true;
}

bool AudioEngine::rearmOverdubPlaybackDuringRecording(
    const std::vector<std::string> &wavPaths,
    const std::vector<float> &gains,
    const int64_t startPositionMs,
    const int64_t sessionTimelineEndMs,
    const std::vector<int64_t> &laneClipStartMs,
    const std::vector<int64_t> &laneClipDurationMs,
    const std::vector<uint8_t> &laneLoopEnabled,
    const std::vector<int64_t> &laneLoopSourceStartMs,
    const std::vector<int64_t> &laneLoopSourceEndMs,
    const std::vector<int64_t> &laneSourceTrimStartMs,
    const std::vector<float> &lanePan) {
    if (!m_isRecording.load(std::memory_order_acquire)) {
        return false;
    }
    return setPlaybackSources(
        wavPaths,
        gains,
        startPositionMs,
        sessionTimelineEndMs,
        laneClipStartMs,
        laneClipDurationMs,
        laneLoopEnabled,
        laneLoopSourceStartMs,
        laneLoopSourceEndMs,
        laneSourceTrimStartMs,
        lanePan,
        false,
        true);
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
            mixTransportFrameAtMonotonicNs(transport_clock::monotonicNowNs());
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
            int64_t sourceTrimStartFrame = 0;

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
                sourceTrimStartFrame =
                    lane.sourceTrimStartFrame.load(std::memory_order_acquire);
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
                                    sourceLoopEndFrame,
                                    sourceTrimStartFrame);
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
                bool expectedPrefetch = false;
                if (m_startupIoPrefetchLogged.compare_exchange_strong(
                        expectedPrefetch,
                        true,
                        std::memory_order_release)) {
                    char stage[96];
                    std::snprintf(
                        stage,
                        sizeof(stage),
                        "io_ring_prefetch_first_batch lane=%zu frames=%d",
                        laneIdx,
                        framesRead);
                    logPlaybackStartupMilestone(stage);
                }
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
        if (currentTransportFrame() >= sessionEndFrame) {
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

    const int64_t transportFrame = currentTransportFrame();

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
        const int64_t sourceTrimStartFrame =
            lane.sourceTrimStartFrame.load(std::memory_order_acquire);

        const int64_t parkedFrame = laneSourceSeekFrameForArm(
            transportFrameAtBlock,
            clipStartFrame,
            loopEnabled,
            sourceLoopStartFrame,
            sourceLoopEndFrame,
            sourceTrimStartFrame);
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
    const std::vector<int64_t> &laneSourceTrimStartMs,
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
                laneSourceTrimStartMs,
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

    bool expectedRender = false;
    if (m_startupFirstRenderLogged.compare_exchange_strong(
            expectedRender,
            true,
            std::memory_order_release)) {
        logPlaybackStartupMilestone("first_render_callback");
    }

    const int64_t callbackMonotonicNs = transport_clock::monotonicNowNs();
    const int64_t effectiveLatencyNs = effectiveOutputLatencyNs();
    const int64_t transportFrameAtBlock =
        mixTransportFrameAtMonotonicNs(callbackMonotonicNs);

    if (effectiveLatencyNs > 0) {
        bool expected = false;
        if (m_outputRenderAheadLogged.compare_exchange_strong(
                expected,
                true,
                std::memory_order_release)) {
            const output_render_ahead::OutputLatencySource source =
                output_render_ahead::outputLatencySource(
                    m_liveOutputLatencyNs.load(std::memory_order_acquire),
                    m_liveOutputLatencyValid.load(std::memory_order_acquire),
                    m_sessionOutputLatencyNs.load(std::memory_order_acquire));
            const char *sourceLabel =
                source == output_render_ahead::OutputLatencySource::LiveHal
                    ? "live_hal"
                    : source == output_render_ahead::OutputLatencySource::SessionProfile
                        ? "session_profile"
                        : "none";
            __android_log_print(
                ANDROID_LOG_INFO,
                "AudioSyncDiag",
                "[OUTPUT_RENDER_AHEAD] source=%s effectiveLatencyNs=%lld "
                "effectiveLatencyFrames=%lld sampleRate=%d",
                sourceLabel,
                static_cast<long long>(effectiveLatencyNs),
                static_cast<long long>(outputLatencyFrames()),
                m_sampleRate);
        }
    }

    const std::size_t outSampleCount = static_cast<std::size_t>(numFrames) *
                                       static_cast<std::size_t>(channels);

    int32_t minFramesReturned = numFrames;
    bool readAnyLane = renderBlock(
        outputInterleaved,
        numFrames,
        channels,
        transportFrameAtBlock,
        RenderBlockInputMode::RingBuffer,
        false,
        false,
        &minFramesReturned);

    if (readAnyLane) {
        capturePlaybackOutputMilestones(
            transportFrameAtBlock,
            outputInterleaved,
            numFrames,
            channels);
    }

    if (m_isPlaying.load(std::memory_order_acquire)) {
        m_masterPlaybackFrame.store(
            transportFrameAtMonotonicNs(transport_clock::monotonicNowNs()),
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
    staging.sourceTrimStartMs = item.sourceTrimStartMs;

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
    const int64_t sourceTrimStartFrame =
        loopEnabled ? 0 : playbackStartFrameFromMs(staging.sourceTrimStartMs, m_sampleRate);
    const int64_t sourceSeekFrame =
        laneSourceSeekFrameForArm(
            commitFrame,
            clipStartFrame,
            loopEnabled,
            sourceLoopStartFrame,
            sourceLoopEndFrame,
            sourceTrimStartFrame);
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
    lane.sourceTrimStartFrame.store(sourceTrimStartFrame, std::memory_order_release);
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
                                      const int64_t sourceTrimStartMs,
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
        item.sourceTrimStartMs = std::max<int64_t>(0, sourceTrimStartMs);
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
        reservedSlot.sourceTrimStartFrame.store(
            loopEnabled ? 0 : playbackStartFrameFromMs(item.sourceTrimStartMs, m_sampleRate),
            std::memory_order_release);
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
