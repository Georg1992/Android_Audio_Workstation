#include "OboeTimestampDiagnostics.h"

#include "AudioStreamConfigurationDiagnostics.h"
#include "AudioSyncLogConfig.h"
#include "TransportClockAnchor.h"

#include <android/log.h>

#include <atomic>
#include <cmath>
#include <cstdint>

namespace oboe_timestamp_diag {
namespace {

constexpr const char *kLogTag = "AudioSyncDiag";
constexpr int32_t kLegacyInitialBurstLogCount = 10;
constexpr int32_t kClockValidationOutputOkLogLimit = 3;
constexpr int64_t kThrottleIntervalNs = 1'000'000'000LL;
constexpr int64_t kUnavailable = -1;

struct DiagThrottle {
    std::atomic<int32_t> invocationCount{0};
    std::atomic<int64_t> lastLogMonotonicNs{0};

    void reset() {
        invocationCount.store(0, std::memory_order_relaxed);
        lastLogMonotonicNs.store(0, std::memory_order_relaxed);
    }

    bool shouldLogLegacyBurst() {
        const int32_t count =
            invocationCount.fetch_add(1, std::memory_order_relaxed) + 1;
        if (count <= kLegacyInitialBurstLogCount) {
            return true;
        }

        const int64_t nowNs = transport_clock::monotonicNowNs();
        int64_t lastNs = lastLogMonotonicNs.load(std::memory_order_relaxed);
        if (nowNs - lastNs < kThrottleIntervalNs) {
            return false;
        }
        return lastLogMonotonicNs.compare_exchange_strong(
            lastNs,
            nowNs,
            std::memory_order_relaxed,
            std::memory_order_relaxed);
    }
};

struct TimestampStabilityTracker {
    std::atomic<int64_t> lastPosition{kUnavailable};
    std::atomic<int64_t> lastTimestampNs{kUnavailable};
    std::atomic<int32_t> coherentAdvanceCount{0};
    std::atomic<bool> stable{false};
};

DiagThrottle g_outputThrottle;
DiagThrottle g_inputThrottle;
std::atomic<bool> g_inputFirstFailureLogged{false};
std::atomic<int32_t> g_outputOkTimestampLogCount{0};
std::atomic<bool> g_outputTimestampValid{false};

TimestampStabilityTracker g_outputTimestampStability;
TimestampStabilityTracker g_inputTimestampStability;

const char *streamStateLabel(const oboe::StreamState state) {
    switch (state) {
        case oboe::StreamState::Uninitialized:
            return "Uninitialized";
        case oboe::StreamState::Unknown:
            return "Unknown";
        case oboe::StreamState::Open:
            return "Open";
        case oboe::StreamState::Starting:
            return "Starting";
        case oboe::StreamState::Started:
            return "Started";
        case oboe::StreamState::Pausing:
            return "Pausing";
        case oboe::StreamState::Paused:
            return "Paused";
        case oboe::StreamState::Flushing:
            return "Flushing";
        case oboe::StreamState::Flushed:
            return "Flushed";
        case oboe::StreamState::Stopping:
            return "Stopping";
        case oboe::StreamState::Stopped:
            return "Stopped";
        case oboe::StreamState::Closing:
            return "Closing";
        case oboe::StreamState::Closed:
            return "Closed";
        case oboe::StreamState::Disconnected:
            return "Disconnected";
        default:
            return "Unknown";
    }
}

void logLatencyEstimateFields(oboe::AudioStream *stream,
                              int64_t &outLatencyFrames,
                              double &outLatencyMs) {
    outLatencyFrames = kUnavailable;
    outLatencyMs = -1.0;
    if (!stream) {
        return;
    }

    const auto latencyResult = stream->calculateLatencyMillis();
    if (!latencyResult) {
        return;
    }

    outLatencyMs = latencyResult.value();
    const int32_t sampleRateHz = stream->getSampleRate();
    if (sampleRateHz > 0 && outLatencyMs >= 0.0) {
        outLatencyFrames = static_cast<int64_t>(
            std::llround(outLatencyMs * static_cast<double>(sampleRateHz) / 1000.0));
    }
}

void queryStreamTimestamp(oboe::AudioStream *stream,
                          int64_t &outFramePosition,
                          int64_t &outTimestampNs,
                          oboe::Result &outResult) {
    outFramePosition = kUnavailable;
    outTimestampNs = kUnavailable;
    outResult = oboe::Result::ErrorNull;

    if (!stream) {
        return;
    }

    const auto timestampResult = stream->getTimestamp(CLOCK_MONOTONIC);
    if (timestampResult) {
        outFramePosition = timestampResult.value().position;
        outTimestampNs = timestampResult.value().timestamp;
        outResult = oboe::Result::OK;
        return;
    }

    outResult = timestampResult.error();
}

TimestampStabilityTracker &trackerForDirection(const bool isInput) {
    return isInput ? g_inputTimestampStability : g_outputTimestampStability;
}

bool updateTimestampStability(TimestampStabilityTracker &tracker,
                              const int64_t framePosition,
                              const int64_t timestampNs) {
    const int64_t previousPosition =
        tracker.lastPosition.load(std::memory_order_relaxed);
    const int64_t previousTimestampNs =
        tracker.lastTimestampNs.load(std::memory_order_relaxed);

    tracker.lastPosition.store(framePosition, std::memory_order_relaxed);
    tracker.lastTimestampNs.store(timestampNs, std::memory_order_relaxed);

    if (previousPosition < 0 || previousTimestampNs < 0) {
        return false;
    }

    if (framePosition > previousPosition && timestampNs > previousTimestampNs) {
        const int32_t advances =
            tracker.coherentAdvanceCount.fetch_add(1, std::memory_order_relaxed) + 1;
        if (advances >= 2) {
            tracker.stable.store(true, std::memory_order_relaxed);
        }
    } else if (framePosition != previousPosition || timestampNs != previousTimestampNs) {
        tracker.coherentAdvanceCount.store(0, std::memory_order_relaxed);
        tracker.stable.store(false, std::memory_order_relaxed);
    }

    return tracker.stable.load(std::memory_order_relaxed);
}

void resetTimestampStabilityTracker(TimestampStabilityTracker &tracker) {
    tracker.lastPosition.store(kUnavailable, std::memory_order_relaxed);
    tracker.lastTimestampNs.store(kUnavailable, std::memory_order_relaxed);
    tracker.coherentAdvanceCount.store(0, std::memory_order_relaxed);
    tracker.stable.store(false, std::memory_order_relaxed);
}

bool shouldLogOutputTimestampClockValidation(const oboe::Result timestampResult,
                                             const int64_t framePosition,
                                             const int64_t timestampNs) {
    if (timestampResult != oboe::Result::OK || framePosition < 0 || timestampNs < 0) {
        return false;
    }

    if (!g_outputTimestampValid.load(std::memory_order_acquire)) {
        g_outputTimestampValid.store(true, std::memory_order_release);
        g_outputOkTimestampLogCount.store(0, std::memory_order_release);
    }

    const int32_t logged =
        g_outputOkTimestampLogCount.load(std::memory_order_relaxed);
    if (logged >= kClockValidationOutputOkLogLimit) {
        return false;
    }
    g_outputOkTimestampLogCount.fetch_add(1, std::memory_order_relaxed);
    return true;
}

bool shouldLogInputTimestampClockValidation(const oboe::Result timestampResult) {
    if (timestampResult == oboe::Result::OK) {
        return false;
    }
    if (g_inputFirstFailureLogged.exchange(true, std::memory_order_acq_rel)) {
        return false;
    }
    return true;
}

} // namespace

void resetOutputTimestampDiagnostics() {
    g_outputThrottle.reset();
    resetTimestampStabilityTracker(g_outputTimestampStability);
    g_outputOkTimestampLogCount.store(0, std::memory_order_release);
    g_outputTimestampValid.store(false, std::memory_order_release);
    audio_stream_config_diag::resetOutputHardwareFloorLogged();
}

void resetInputTimestampDiagnostics() {
    g_inputThrottle.reset();
    resetTimestampStabilityTracker(g_inputTimestampStability);
    g_inputFirstFailureLogged.store(false, std::memory_order_release);
}

bool recordTimestampSampleAndCheckStable(oboe::AudioStream *const stream,
                                         const int64_t framePosition,
                                         const int64_t timestampNs) {
    if (!stream) {
        return false;
    }
    const bool isInput = stream->getDirection() == oboe::Direction::Input;
    return updateTimestampStability(
        trackerForDirection(isInput),
        framePosition,
        timestampNs);
}

void maybeLogOutputTimestamp(oboe::AudioStream *const stream,
                             const int64_t transportFrame,
                             const int64_t masterPlaybackFrame,
                             const int32_t callbackFrames) {
    int64_t streamFramePosition = kUnavailable;
    int64_t timestampNs = kUnavailable;
    oboe::Result timestampResult = oboe::Result::ErrorNull;
    queryStreamTimestamp(stream, streamFramePosition, timestampNs, timestampResult);

    const bool clockValidation =
        audio_sync_log_config::clockValidationMode() &&
        !audio_sync_log_config::rawTimestampSpamEnabled();
    if (clockValidation) {
        if (!shouldLogOutputTimestampClockValidation(
                timestampResult, streamFramePosition, timestampNs)) {
            return;
        }
    } else if (!g_outputThrottle.shouldLogLegacyBurst()) {
        return;
    }

    int64_t estimatedLatencyFrames = kUnavailable;
    double estimatedLatencyMs = -1.0;
    logLatencyEstimateFields(stream, estimatedLatencyFrames, estimatedLatencyMs);

    const int32_t sampleRateHz = stream ? stream->getSampleRate() : 0;
    const int32_t framesPerBurst = stream ? stream->getFramesPerBurst() : 0;
    const int32_t bufferSizeFrames = stream ? stream->getBufferSizeInFrames() : 0;
    const int32_t bufferCapacityFrames =
        stream ? stream->getBufferCapacityInFrames() : 0;
    const int32_t performanceMode =
        stream ? static_cast<int32_t>(stream->getPerformanceMode()) : 0;
    const int32_t sharingMode =
        stream ? static_cast<int32_t>(stream->getSharingMode()) : 0;
    const oboe::StreamState streamState =
        stream ? stream->getState() : oboe::StreamState::Uninitialized;

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[OUTPUT_TIMESTAMP] "
        "streamFramePosition=%lld "
        "timestampNs=%lld "
        "transportFrame=%lld "
        "masterPlaybackFrame=%lld "
        "estimatedOutputLatencyFrames=%lld "
        "estimatedOutputLatencyMs=%.3f "
        "sampleRateHz=%d "
        "framesPerBurst=%d "
        "bufferSizeFrames=%d "
        "bufferCapacityFrames=%d "
        "performanceMode=%d "
        "sharingMode=%d "
        "callbackFrames=%d "
        "streamState=%s "
        "result=%s",
        static_cast<long long>(streamFramePosition),
        static_cast<long long>(timestampNs),
        static_cast<long long>(transportFrame),
        static_cast<long long>(masterPlaybackFrame),
        static_cast<long long>(estimatedLatencyFrames),
        estimatedLatencyMs,
        sampleRateHz,
        framesPerBurst,
        bufferSizeFrames,
        bufferCapacityFrames,
        performanceMode,
        sharingMode,
        callbackFrames,
        streamStateLabel(streamState),
        oboe::convertToText(timestampResult));
}

void maybeLogInputTimestamp(oboe::AudioStream *const stream,
                            const int64_t transportFrameAtRead,
                            const int32_t readFrames) {
    int64_t streamFramePosition = kUnavailable;
    int64_t timestampNs = kUnavailable;
    oboe::Result timestampResult = oboe::Result::ErrorNull;
    queryStreamTimestamp(stream, streamFramePosition, timestampNs, timestampResult);

    const bool clockValidation =
        audio_sync_log_config::clockValidationMode() &&
        !audio_sync_log_config::rawTimestampSpamEnabled();
    if (clockValidation) {
        if (!shouldLogInputTimestampClockValidation(timestampResult)) {
            return;
        }
    } else if (!g_inputThrottle.shouldLogLegacyBurst()) {
        return;
    }

    int64_t estimatedLatencyFrames = kUnavailable;
    double estimatedLatencyMs = -1.0;
    logLatencyEstimateFields(stream, estimatedLatencyFrames, estimatedLatencyMs);

    const int32_t sampleRateHz = stream ? stream->getSampleRate() : 0;
    const int32_t framesPerBurst = stream ? stream->getFramesPerBurst() : 0;
    const int32_t bufferSizeFrames = stream ? stream->getBufferSizeInFrames() : 0;
    const int32_t bufferCapacityFrames =
        stream ? stream->getBufferCapacityInFrames() : 0;
    const oboe::StreamState streamState =
        stream ? stream->getState() : oboe::StreamState::Uninitialized;

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[INPUT_TIMESTAMP] "
        "streamFramePosition=%lld "
        "timestampNs=%lld "
        "transportFrameAtRead=%lld "
        "readFrames=%d "
        "estimatedInputLatencyFrames=%lld "
        "estimatedInputLatencyMs=%.3f "
        "sampleRateHz=%d "
        "framesPerBurst=%d "
        "bufferSizeFrames=%d "
        "bufferCapacityFrames=%d "
        "streamState=%s "
        "result=%s",
        static_cast<long long>(streamFramePosition),
        static_cast<long long>(timestampNs),
        static_cast<long long>(transportFrameAtRead),
        readFrames,
        static_cast<long long>(estimatedLatencyFrames),
        estimatedLatencyMs,
        sampleRateHz,
        framesPerBurst,
        bufferSizeFrames,
        bufferCapacityFrames,
        streamStateLabel(streamState),
        oboe::convertToText(timestampResult));
}

} // namespace oboe_timestamp_diag
