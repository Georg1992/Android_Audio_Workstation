#include "DeviceLatencyBudgetDiagnostics.h"

#include "AudioEngine.h"
#include "TransportClockAnchor.h"

#include <android/log.h>
#include <oboe/Oboe.h>

#include <atomic>
#include <cmath>
#include <memory>

namespace device_latency_budget_diag {
namespace {

constexpr const char *kLogTag = "AudioSyncDiag";

std::atomic<bool> g_logged{false};

const char *audioApiLabel(const oboe::AudioApi api) {
    switch (api) {
        case oboe::AudioApi::AAudio:
            return "AAudio";
        case oboe::AudioApi::OpenSLES:
            return "OpenSLES";
        default:
            return "Unknown";
    }
}

const char *performanceModeLabel(const oboe::PerformanceMode mode) {
    switch (mode) {
        case oboe::PerformanceMode::LowLatency:
            return "LowLatency";
        case oboe::PerformanceMode::PowerSaving:
            return "PowerSaving";
        case oboe::PerformanceMode::None:
            return "None";
        default:
            return "Other";
    }
}

const char *sharingModeLabel(const oboe::SharingMode mode) {
    switch (mode) {
        case oboe::SharingMode::Exclusive:
            return "Exclusive";
        case oboe::SharingMode::Shared:
            return "Shared";
        default:
            return "Other";
    }
}

double queryEstimatedLatencyMs(oboe::AudioStream *const stream) {
    if (!stream) {
        return -1.0;
    }
    const auto latencyResult = stream->calculateLatencyMillis();
    if (!latencyResult) {
        return -1.0;
    }
    return latencyResult.value();
}

int64_t framesToMs(const int64_t frames, const int32_t sampleRateHz) {
    if (sampleRateHz <= 0) {
        return 0;
    }
    return (frames * 1000LL) / static_cast<int64_t>(sampleRateHz);
}

struct StreamLogFields {
    const char *audioApi = "n/a";
    const char *performanceMode = "n/a";
    const char *sharingMode = "n/a";
    int32_t sampleRateHz = 0;
    int32_t framesPerBurst = 0;
    int32_t bufferSizeFrames = 0;
    int32_t bufferCapacityFrames = 0;
};

StreamLogFields fieldsFromStream(oboe::AudioStream *const stream) {
    StreamLogFields fields;
    if (!stream) {
        return fields;
    }
    fields.audioApi = audioApiLabel(stream->getAudioApi());
    fields.performanceMode = performanceModeLabel(stream->getPerformanceMode());
    fields.sharingMode = sharingModeLabel(stream->getSharingMode());
    fields.sampleRateHz = stream->getSampleRate();
    fields.framesPerBurst = stream->getFramesPerBurst();
    fields.bufferSizeFrames = stream->getBufferSizeInFrames();
    fields.bufferCapacityFrames = stream->getBufferCapacityInFrames();
    return fields;
}

StreamLogFields fieldsFromSnapshot(
    const dawengine::AudioEngine::OboeStreamSnapshot &snapshot) {
    StreamLogFields fields;
    if (snapshot.sampleRateHz <= 0) {
        return fields;
    }
    fields.performanceMode =
        performanceModeLabel(static_cast<oboe::PerformanceMode>(snapshot.performanceMode));
    fields.sharingMode =
        sharingModeLabel(static_cast<oboe::SharingMode>(snapshot.sharingMode));
    fields.sampleRateHz = snapshot.sampleRateHz;
    fields.framesPerBurst = snapshot.framesPerBurst;
    fields.bufferSizeFrames = snapshot.bufferSizeInFrames;
    fields.bufferCapacityFrames = snapshot.bufferCapacityInFrames;
    return fields;
}

struct ContributionBreakdown {
    double outputContributionMs = 0.0;
    double inputContributionMs = 0.0;
    double startupContributionMs = 0.0;
    double unknownContributionMs = 0.0;
};

ContributionBreakdown deriveContributions(const double clockCaptureDeltaMs,
                                         const double estimatedOutputLatencyMs,
                                         const double estimatedInputLatencyMs,
                                         const double armToFirstInputMs) {
    ContributionBreakdown breakdown;
    breakdown.outputContributionMs =
        estimatedOutputLatencyMs >= 0.0 ? estimatedOutputLatencyMs : 0.0;
    breakdown.inputContributionMs =
        estimatedInputLatencyMs >= 0.0 ? estimatedInputLatencyMs : 0.0;
    if (armToFirstInputMs >= 0.0) {
        breakdown.startupContributionMs =
            std::max(0.0, armToFirstInputMs - breakdown.inputContributionMs);
    }
    breakdown.unknownContributionMs =
        clockCaptureDeltaMs - breakdown.outputContributionMs -
        breakdown.inputContributionMs - breakdown.startupContributionMs;
    return breakdown;
}

} // namespace

void resetDeviceLatencyBudgetDiagnostics() {
    g_logged.store(false, std::memory_order_release);
}

void logDeviceLatencyBudget(dawengine::AudioEngine *const engine,
                            const int64_t legacyFirstSampleTransportFrame,
                            const int64_t appReceiveMonotonicNs) {
    if (!engine || legacyFirstSampleTransportFrame < 0 ||
        g_logged.exchange(true, std::memory_order_acq_rel)) {
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
    const int64_t clockCaptureDeltaMs =
        framesToMs(clockCaptureTransportFrame - legacyFirstSampleTransportFrame,
                   anchor.sampleRateHz);

    const dawengine::AudioEngine::PlaybackSessionTimings timings =
        engine->playbackSessionTimings();
    const double armToFirstInputMs =
        timings.playbackArmSteadyNs > 0 &&
                timings.firstInputSampleSteadyNs > timings.playbackArmSteadyNs
            ? static_cast<double>(timings.firstInputSampleSteadyNs -
                                  timings.playbackArmSteadyNs) /
                  1'000'000.0
            : -1.0;

    oboe::AudioStream *const outputStream = engine->outputStreamForDiagnostics().get();
    oboe::AudioStream *const inputStream = engine->inputStreamForDiagnostics().get();
    StreamLogFields fields =
        outputStream != nullptr
            ? fieldsFromStream(outputStream)
            : fieldsFromSnapshot(engine->outputStreamSnapshot());

    const double estimatedOutputLatencyMs = queryEstimatedLatencyMs(outputStream);
    const double estimatedInputLatencyMs = queryEstimatedLatencyMs(inputStream);
    const ContributionBreakdown breakdown = deriveContributions(
        static_cast<double>(clockCaptureDeltaMs),
        estimatedOutputLatencyMs,
        estimatedInputLatencyMs,
        armToFirstInputMs);

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[DEVICE_LATENCY_BUDGET] "
        "audioApi=%s "
        "performanceMode=%s "
        "sharingMode=%s "
        "sampleRate=%d "
        "framesPerBurst=%d "
        "bufferSizeFrames=%d "
        "bufferCapacityFrames=%d "
        "estimatedOutputLatencyMs=%.3f "
        "estimatedInputLatencyMs=%.3f "
        "clockCaptureDeltaMs=%lld "
        "armToFirstInputMs=%.3f "
        "outputContributionMs=%.3f "
        "inputContributionMs=%.3f "
        "startupContributionMs=%.3f "
        "unknownContributionMs=%.3f",
        fields.audioApi,
        fields.performanceMode,
        fields.sharingMode,
        fields.sampleRateHz,
        fields.framesPerBurst,
        fields.bufferSizeFrames,
        fields.bufferCapacityFrames,
        estimatedOutputLatencyMs,
        estimatedInputLatencyMs,
        static_cast<long long>(clockCaptureDeltaMs),
        armToFirstInputMs,
        breakdown.outputContributionMs,
        breakdown.inputContributionMs,
        breakdown.startupContributionMs,
        breakdown.unknownContributionMs);
}

} // namespace device_latency_budget_diag
