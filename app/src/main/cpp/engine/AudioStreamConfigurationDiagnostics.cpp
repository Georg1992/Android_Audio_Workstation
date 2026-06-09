#include "AudioStreamConfigurationDiagnostics.h"

#include <android/log.h>

#include <cstdio>

#include <atomic>

namespace audio_stream_config_diag {
namespace {

constexpr const char *kLogTag = "AudioSyncDiag";
constexpr double kMaxSaneHalLatencyMs = 500.0;

std::atomic<bool> g_outputHardwareFloorLogged{false};

bool halLatencyMsIsSane(const double latencyMs) {
    return latencyMs >= 0.0 && latencyMs <= kMaxSaneHalLatencyMs;
}

const char *audioApiLabel(const oboe::AudioApi api) {
    switch (api) {
        case oboe::AudioApi::Unspecified:
            return "Unspecified";
        case oboe::AudioApi::AAudio:
            return "AAudio";
        case oboe::AudioApi::OpenSLES:
            return "OpenSLES";
        default:
            return "Other";
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
        case oboe::SharingMode::Shared:
            return "Shared";
        case oboe::SharingMode::Exclusive:
            return "Exclusive";
        default:
            return "Other";
    }
}

int32_t performanceModeMatches(const oboe::PerformanceMode requested,
                               const oboe::PerformanceMode actual) {
    if (requested == oboe::PerformanceMode::None) {
        return 1;
    }
    return requested == actual ? 1 : 0;
}

int32_t sharingModeMatches(const oboe::SharingMode requested,
                           const oboe::SharingMode actual) {
    if (requested == oboe::SharingMode::Shared ||
        requested == oboe::SharingMode::Exclusive) {
        return requested == actual ? 1 : 0;
    }
    return 1;
}

int32_t audioApiMatches(const oboe::AudioApi requested, const oboe::AudioApi actual) {
    if (requested == oboe::AudioApi::Unspecified) {
        return 1;
    }
    return requested == actual ? 1 : 0;
}

} // namespace

void logOpenedStream(const char *const streamRole,
                     const StreamOpenRequest &requested,
                     oboe::AudioStream *const stream,
                     const oboe::Result openResult) {
    const char *role = streamRole ? streamRole : "unknown";
    if (!stream || openResult != oboe::Result::OK) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "[AUDIO_STREAM_CONFIGURATION] "
            "stream=%s "
            "openResult=%s "
            "requestedAudioApi=%s "
            "actualAudioApi=n/a "
            "requestedPerformanceMode=%s "
            "actualPerformanceMode=n/a "
            "requestedSharingMode=%s "
            "actualSharingMode=n/a "
            "requestedSampleRate=%d "
            "actualSampleRate=n/a "
            "requestedBufferSizeFrames=-1 "
            "actualBufferSizeFrames=n/a "
            "requestedChannelCount=%d "
            "actualChannelCount=n/a "
            "performanceModeGranted=n/a "
            "sharingModeGranted=n/a "
            "audioApiGranted=n/a",
            role,
            oboe::convertToText(openResult),
            audioApiLabel(requested.audioApi),
            performanceModeLabel(requested.performanceMode),
            sharingModeLabel(requested.sharingMode),
            requested.sampleRateHz,
            requested.channelCount);
        return;
    }

    const oboe::AudioApi actualAudioApi = stream->getAudioApi();
    const oboe::PerformanceMode actualPerformanceMode = stream->getPerformanceMode();
    const oboe::SharingMode actualSharingMode = stream->getSharingMode();
    const int32_t actualSampleRateHz = stream->getSampleRate();
    const int32_t actualBufferSizeFrames = stream->getBufferSizeInFrames();
    const int32_t actualChannelCount = stream->getChannelCount();
    const long long requestedBufferSizeFrames =
        requested.bufferSizeInFrames > 0
            ? static_cast<long long>(requested.bufferSizeInFrames)
            : -1LL;

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[AUDIO_STREAM_CONFIGURATION] "
        "stream=%s "
        "openResult=%s "
        "requestedAudioApi=%s "
        "actualAudioApi=%s "
        "requestedPerformanceMode=%s "
        "actualPerformanceMode=%s "
        "requestedSharingMode=%s "
        "actualSharingMode=%s "
        "requestedSampleRate=%d "
        "actualSampleRate=%d "
        "requestedBufferSizeFrames=%lld "
        "actualBufferSizeFrames=%d "
        "requestedChannelCount=%d "
        "actualChannelCount=%d "
        "performanceModeGranted=%d "
        "sharingModeGranted=%d "
        "audioApiGranted=%d "
        "framesPerBurst=%d "
        "bufferCapacityFrames=%d",
        role,
        oboe::convertToText(openResult),
        audioApiLabel(requested.audioApi),
        audioApiLabel(actualAudioApi),
        performanceModeLabel(requested.performanceMode),
        performanceModeLabel(actualPerformanceMode),
        sharingModeLabel(requested.sharingMode),
        sharingModeLabel(actualSharingMode),
        requested.sampleRateHz,
        actualSampleRateHz,
        requestedBufferSizeFrames,
        actualBufferSizeFrames,
        requested.channelCount,
        actualChannelCount,
        performanceModeMatches(requested.performanceMode, actualPerformanceMode),
        sharingModeMatches(requested.sharingMode, actualSharingMode),
        audioApiMatches(requested.audioApi, actualAudioApi),
        stream->getFramesPerBurst(),
        stream->getBufferCapacityInFrames());
}

void logOutputHardwareFloor(const char *const context,
                            const StreamOpenRequest &requested,
                            oboe::AudioStream *const stream) {
    const char *ctx = context ? context : "unknown";
    if (!stream) {
        __android_log_print(
            ANDROID_LOG_INFO,
            kLogTag,
            "[OUTPUT_HARDWARE_FLOOR] "
            "context=%s "
            "halReportedLatencyMs=n/a "
            "reason=no_stream",
            ctx);
        return;
    }

    const int32_t sampleRateHz = stream->getSampleRate();
    const int32_t framesPerBurst = stream->getFramesPerBurst();
    const int32_t bufferSizeFrames = stream->getBufferSizeInFrames();
    const int32_t bufferCapacityFrames = stream->getBufferCapacityInFrames();
    const oboe::PerformanceMode actualPerformanceMode = stream->getPerformanceMode();
    const int32_t performanceModeGranted =
        performanceModeMatches(requested.performanceMode, actualPerformanceMode);
    const int32_t lowLatencyPathDenied =
        requested.performanceMode == oboe::PerformanceMode::LowLatency &&
                actualPerformanceMode != oboe::PerformanceMode::LowLatency
            ? 1
            : 0;

    double halReportedLatencyMs = -1.0;
    const auto latencyResult = stream->calculateLatencyMillis();
    if (latencyResult) {
        halReportedLatencyMs = latencyResult.value();
    }

    const double bufferSizeMs =
        sampleRateHz > 0
            ? static_cast<double>(bufferSizeFrames) * 1000.0 /
                  static_cast<double>(sampleRateHz)
            : -1.0;
    const double burstMs =
        sampleRateHz > 0
            ? static_cast<double>(framesPerBurst) * 1000.0 /
                  static_cast<double>(sampleRateHz)
            : -1.0;

    char halLatencyBuffer[32] = {};
    const char *halLatencyLabel = "n/a";
    if (halReportedLatencyMs >= 0.0) {
        snprintf(
            halLatencyBuffer,
            sizeof(halLatencyBuffer),
            "%.3f",
            halReportedLatencyMs);
        halLatencyLabel = halLatencyBuffer;
    }

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[OUTPUT_HARDWARE_FLOOR] "
        "context=%s "
        "halReportedLatencyMs=%s "
        "bufferSizeFrames=%d "
        "bufferSizeMs=%.3f "
        "framesPerBurst=%d "
        "burstMs=%.3f "
        "bufferCapacityFrames=%d "
        "audioApi=%s "
        "performanceModeRequested=%s "
        "performanceModeActual=%s "
        "performanceModeGranted=%d "
        "lowLatencyPathDenied=%d "
        "sharingMode=%s "
        "sampleRateHz=%d "
        "latencySource=hal_query "
        "scope=hal_playback_only",
        ctx,
        halLatencyLabel,
        bufferSizeFrames,
        bufferSizeMs,
        framesPerBurst,
        burstMs,
        bufferCapacityFrames,
        audioApiLabel(stream->getAudioApi()),
        performanceModeLabel(requested.performanceMode),
        performanceModeLabel(actualPerformanceMode),
        performanceModeGranted,
        lowLatencyPathDenied,
        sharingModeLabel(stream->getSharingMode()),
        sampleRateHz);
}

void resetOutputHardwareFloorLogged() {
    g_outputHardwareFloorLogged.store(false, std::memory_order_release);
}

void maybeLogOutputHardwareFloorOnce(const char *const context,
                                     const StreamOpenRequest &requested,
                                     oboe::AudioStream *const stream) {
    if (!stream || g_outputHardwareFloorLogged.load(std::memory_order_acquire)) {
        return;
    }

    const auto latencyResult = stream->calculateLatencyMillis();
    if (!latencyResult || !halLatencyMsIsSane(latencyResult.value())) {
        return;
    }

    bool expected = false;
    if (!g_outputHardwareFloorLogged.compare_exchange_strong(
            expected,
            true,
            std::memory_order_acq_rel)) {
        return;
    }

    logOutputHardwareFloor(context, requested, stream);
}

} // namespace audio_stream_config_diag
