#include "OutputRenderAhead.h"

#include "TransportClockAnchor.h"

#include <oboe/Oboe.h>

#include <cmath>

namespace output_render_ahead {
namespace {

constexpr int64_t kNsPerSecond = 1'000'000'000LL;

} // namespace

int64_t latencyNsFromHalMillis(const double latencyMs) {
    if (!std::isfinite(latencyMs) || latencyMs <= 0.0) {
        return 0;
    }
    return static_cast<int64_t>(std::llround(latencyMs * 1'000'000.0));
}

int64_t latencyFramesFromNs(const int64_t latencyNs, const int32_t sampleRateHz) {
    if (latencyNs <= 0 || sampleRateHz <= 0) {
        return 0;
    }
    return (latencyNs * static_cast<int64_t>(sampleRateHz)) / kNsPerSecond;
}

int64_t effectiveOutputLatencyNs(const int64_t liveLatencyNs,
                                 const bool liveLatencyValid,
                                 const int64_t sessionConfiguredLatencyNs) {
    if (liveLatencyValid && liveLatencyNs > 0) {
        return liveLatencyNs;
    }
    if (sessionConfiguredLatencyNs > 0) {
        return sessionConfiguredLatencyNs;
    }
    return 0;
}

OutputLatencySource outputLatencySource(const int64_t liveLatencyNs,
                                        const bool liveLatencyValid,
                                        const int64_t sessionConfiguredLatencyNs) {
    if (liveLatencyValid && liveLatencyNs > 0) {
        return OutputLatencySource::LiveHal;
    }
    if (sessionConfiguredLatencyNs > 0) {
        return OutputLatencySource::SessionProfile;
    }
    return OutputLatencySource::None;
}

int64_t mixTransportFrameAtCallback(const int64_t callbackMonotonicNs,
                                    const int64_t effectiveLatencyNs,
                                    const transport_clock::TransportClockAnchor &anchor,
                                    const int64_t offlineTransportFrameFallback,
                                    const int32_t sampleRateHz) {
    if (effectiveLatencyNs <= 0) {
        if (anchor.isValid()) {
            return anchor.transportFrameAt(callbackMonotonicNs);
        }
        return offlineTransportFrameFallback;
    }
    if (anchor.isValid()) {
        return anchor.transportFrameAt(callbackMonotonicNs + effectiveLatencyNs);
    }
    const int32_t rateHz = sampleRateHz > 0 ? sampleRateHz : anchor.sampleRateHz;
    return offlineTransportFrameFallback + latencyFramesFromNs(effectiveLatencyNs, rateHz);
}

void refreshLiveOutputLatencyFromStream(oboe::AudioStream *const stream,
                                        int64_t &outLiveLatencyNs,
                                        bool &outLiveLatencyValid) {
    if (!stream) {
        outLiveLatencyNs = 0;
        outLiveLatencyValid = false;
        return;
    }
    const auto latencyResult = stream->calculateLatencyMillis();
    if (!latencyResult) {
        return;
    }
    const int64_t latencyNs = latencyNsFromHalMillis(latencyResult.value());
    if (latencyNs <= 0) {
        return;
    }
    outLiveLatencyNs = latencyNs;
    outLiveLatencyValid = true;
}

} // namespace output_render_ahead
