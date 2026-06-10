#include "TransportClockDiagnostics.h"

#include "AudioEngine.h"
#include "AudioSyncLogConfig.h"
#include "DeviceLatencyBudgetDiagnostics.h"

#include <android/log.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>

namespace transport_clock_diag {
namespace {

constexpr const char *kLogTag = "AudioSyncDiag";
constexpr int64_t kLegacyThrottleIntervalNs = 1'000'000'000LL;
constexpr int64_t kUnavailable = -1;

struct DiagThrottle {
    std::atomic<int32_t> sessionLogCount{0};
    std::atomic<int64_t> lastLogMonotonicNs{0};

    void reset() {
        sessionLogCount.store(0, std::memory_order_relaxed);
        lastLogMonotonicNs.store(0, std::memory_order_relaxed);
    }

    bool shouldLogClockValidation(const int32_t maxLogsPerSession) {
        const int32_t count =
            sessionLogCount.fetch_add(1, std::memory_order_relaxed) + 1;
        return count <= maxLogsPerSession;
    }

    bool shouldLogLegacyBurstThenPeriodic(const int32_t initialBurstCount) {
        const int32_t count =
            sessionLogCount.fetch_add(1, std::memory_order_relaxed) + 1;
        const int64_t nowNs = transport_clock::monotonicNowNs();
        if (count <= initialBurstCount) {
            lastLogMonotonicNs.store(nowNs, std::memory_order_relaxed);
            return true;
        }

        int64_t lastNs = lastLogMonotonicNs.load(std::memory_order_relaxed);
        if (lastNs > 0 && nowNs - lastNs < kLegacyThrottleIntervalNs) {
            return false;
        }
        lastLogMonotonicNs.store(nowNs, std::memory_order_relaxed);
        return true;
    }
};

DiagThrottle g_outputThrottle;
DiagThrottle g_inputThrottle;
std::atomic<bool> g_placementComparisonLogged{false};
std::atomic<int64_t> g_lastPlacementClockDeltaMs{-1};

int64_t framesToMs(const int64_t deltaFrames, const int32_t sampleRateHz) {
    if (sampleRateHz <= 0) {
        return 0;
    }
    return (deltaFrames * 1000LL) / static_cast<int64_t>(sampleRateHz);
}

} // namespace

void resetTransportClockDiagnostics() {
    g_outputThrottle.reset();
    g_inputThrottle.reset();
    g_placementComparisonLogged.store(false, std::memory_order_release);
    g_lastPlacementClockDeltaMs.store(-1, std::memory_order_release);
    device_latency_budget_diag::resetDeviceLatencyBudgetDiagnostics();
}

int64_t lastPlacementClockDeltaMs() {
    return g_lastPlacementClockDeltaMs.load(std::memory_order_acquire);
}

void logTransportClockAnchor(const transport_clock::TransportClockAnchor &anchor,
                             const char *const reason) {
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[TRANSPORT_CLOCK_ANCHOR] "
        "transportStartFrame=%lld "
        "monotonicStartNs=%lld "
        "sampleRate=%d "
        "reason=%s",
        static_cast<long long>(anchor.transportStartFrame),
        static_cast<long long>(anchor.monotonicStartNs),
        anchor.sampleRateHz,
        reason ? reason : "unknown");
}

void logPlacementClockComparison(dawengine::AudioEngine *const engine,
                                 const char *const mode,
                                 const int64_t legacyFirstSampleTransportFrame,
                                 const int64_t appReceiveMonotonicNs) {
    if (!engine || legacyFirstSampleTransportFrame < 0 ||
        g_placementComparisonLogged.exchange(true, std::memory_order_acq_rel)) {
        return;
    }

    const transport_clock::TransportClockAnchor anchor = engine->transportClockAnchor();
    if (!anchor.isValid()) {
        return;
    }

    const int64_t inputLatencyNs = engine->sessionInputLatencyNs();
    const int64_t estimatedCaptureNs =
        inputLatencyNs > 0 ? appReceiveMonotonicNs - inputLatencyNs : appReceiveMonotonicNs;
    const int64_t clockCaptureTransportFrame =
        anchor.transportFrameAt(estimatedCaptureNs);
    const int64_t deltaFrames =
        clockCaptureTransportFrame - legacyFirstSampleTransportFrame;
    const int64_t deltaMs =
        framesToMs(deltaFrames, anchor.sampleRateHz);
    g_lastPlacementClockDeltaMs.store(deltaMs, std::memory_order_release);

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[PLACEMENT_CLOCK_COMPARISON] "
        "mode=%s "
        "legacyFirstSampleTransportFrame=%lld "
        "clockCaptureTransportFrame=%lld "
        "deltaFrames=%lld "
        "deltaMs=%lld "
        "inputLatencyNs=%lld "
        "appReceiveNs=%lld "
        "estimatedCaptureNs=%lld",
        mode ? mode : "unknown",
        static_cast<long long>(legacyFirstSampleTransportFrame),
        static_cast<long long>(clockCaptureTransportFrame),
        static_cast<long long>(deltaFrames),
        static_cast<long long>(deltaMs),
        static_cast<long long>(inputLatencyNs),
        static_cast<long long>(appReceiveMonotonicNs),
        static_cast<long long>(estimatedCaptureNs));
}

void maybeLogOutputClockCorrelation(dawengine::AudioEngine *const engine,
                                    oboe::AudioStream *const stream,
                                    const int64_t callbackMonotonicNs,
                                    const int32_t numFrames) {
    if (!engine) {
        return;
    }

    const bool clockValidation =
        audio_sync_log_config::clockValidationMode() &&
        !audio_sync_log_config::rawTimestampSpamEnabled();
    if (clockValidation) {
        if (!g_outputThrottle.shouldLogClockValidation(
                audio_sync_log_config::outputClockCorrelationInitialBurstCount())) {
            return;
        }
    } else if (!g_outputThrottle.shouldLogLegacyBurstThenPeriodic(
                   audio_sync_log_config::outputClockCorrelationInitialBurstCount())) {
        return;
    }

    const transport_clock::TransportClockAnchor anchor = engine->transportClockAnchor();
    if (!anchor.isValid()) {
        return;
    }

    const int64_t renderedTransportFrame = engine->transportFrame();
    const int64_t anchorTransportFrame = anchor.transportFrameAt(callbackMonotonicNs);
    const int64_t mixTransportFrame =
        engine->mixTransportFrameAtMonotonicNs(callbackMonotonicNs);

    int64_t streamFramePosition = kUnavailable;
    int64_t streamTimestampNs = kUnavailable;
    if (stream) {
        const auto timestampResult = stream->getTimestamp(CLOCK_MONOTONIC);
        if (timestampResult) {
            streamFramePosition = timestampResult.value().position;
            streamTimestampNs = timestampResult.value().timestamp;
        }
    }

    const int64_t effectiveLatencyNs = engine->effectiveOutputLatencyNs();
    const int64_t estimatedPresentationNs =
        effectiveLatencyNs > 0 ? callbackMonotonicNs + effectiveLatencyNs : kUnavailable;
    const int64_t estimatedAudibleTransportFrame =
        effectiveLatencyNs > 0
            ? anchor.transportFrameAt(callbackMonotonicNs)
            : anchorTransportFrame;

    const int64_t deltaFrames = renderedTransportFrame - anchorTransportFrame;
    const int64_t mixAheadFrames = mixTransportFrame - renderedTransportFrame;

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[OUTPUT_CLOCK_CORRELATION] "
        "callbackNs=%lld "
        "anchorTransportFrame=%lld "
        "renderedTransportFrame=%lld "
        "mixTransportFrame=%lld "
        "effectiveLatencyNs=%lld "
        "streamFramePosition=%lld "
        "streamTimestampNs=%lld "
        "estimatedPresentationNs=%lld "
        "estimatedAudibleTransportFrame=%lld "
        "deltaFrames=%lld "
        "mixAheadFrames=%lld "
        "callbackFrames=%d",
        static_cast<long long>(callbackMonotonicNs),
        static_cast<long long>(anchorTransportFrame),
        static_cast<long long>(renderedTransportFrame),
        static_cast<long long>(mixTransportFrame),
        static_cast<long long>(effectiveLatencyNs),
        static_cast<long long>(streamFramePosition),
        static_cast<long long>(streamTimestampNs),
        static_cast<long long>(estimatedPresentationNs),
        static_cast<long long>(estimatedAudibleTransportFrame),
        static_cast<long long>(deltaFrames),
        static_cast<long long>(mixAheadFrames),
        numFrames);
}

void maybeLogInputCaptureCorrelation(dawengine::AudioEngine *const engine,
                                     const int64_t bufferIndex,
                                     const int64_t appReceiveMonotonicNs,
                                     const int32_t readFrames) {
    if (!engine || readFrames <= 0) {
        return;
    }

    const bool clockValidation =
        audio_sync_log_config::clockValidationMode() &&
        !audio_sync_log_config::rawTimestampSpamEnabled();
    if (clockValidation) {
        if (bufferIndex != 0 ||
            !g_inputThrottle.shouldLogClockValidation(1)) {
            return;
        }
    } else if (!g_inputThrottle.shouldLogLegacyBurstThenPeriodic(
                   audio_sync_log_config::inputCaptureCorrelationInitialBurstCount())) {
        return;
    }

    const transport_clock::TransportClockAnchor anchor = engine->transportClockAnchor();
    if (!anchor.isValid()) {
        return;
    }

    const int64_t inputLatencyNs = engine->sessionInputLatencyNs();
    const int64_t estimatedCaptureNs =
        inputLatencyNs > 0 ? appReceiveMonotonicNs - inputLatencyNs : appReceiveMonotonicNs;
    const int64_t transportFrameAtReceive =
        anchor.transportFrameAt(appReceiveMonotonicNs);
    const int64_t transportFrameAtCapture =
        anchor.transportFrameAt(estimatedCaptureNs);
    const int64_t captureMinusReceiveFrames =
        transportFrameAtCapture - transportFrameAtReceive;

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[INPUT_CAPTURE_CORRELATION] "
        "bufferIndex=%lld "
        "appReceiveNs=%lld "
        "readFrames=%d "
        "inputLatencyNs=%lld "
        "estimatedCaptureNs=%lld "
        "transportFrameAtReceive=%lld "
        "transportFrameAtCapture=%lld "
        "captureMinusReceiveFrames=%lld",
        static_cast<long long>(bufferIndex),
        static_cast<long long>(appReceiveMonotonicNs),
        readFrames,
        static_cast<long long>(inputLatencyNs),
        static_cast<long long>(estimatedCaptureNs),
        static_cast<long long>(transportFrameAtReceive),
        static_cast<long long>(transportFrameAtCapture),
        static_cast<long long>(captureMinusReceiveFrames));
}

} // namespace transport_clock_diag
