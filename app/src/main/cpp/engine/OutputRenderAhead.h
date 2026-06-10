#pragma once

#include "TransportClockAnchor.h"

#include <cstdint>

namespace oboe {
class AudioStream;
} // namespace oboe

namespace output_render_ahead {

enum class OutputLatencySource {
    None = 0,
    LiveHal = 1,
    SessionProfile = 2,
};

/** HAL [calculateLatencyMillis] → integer nanoseconds (microsecond rounding). */
int64_t latencyNsFromHalMillis(const double latencyMs);

/** Same frame math as [transport_clock::TransportClockAnchor::transportFrameAt]. */
int64_t latencyFramesFromNs(const int64_t latencyNs, const int32_t sampleRateHz);

/** Live HAL when valid; otherwise 0 (no render-ahead). Session profile is transport-only. */
int64_t effectiveOutputLatencyNs(const int64_t liveLatencyNs,
                                 const bool liveLatencyValid,
                                 const int64_t sessionConfiguredLatencyNs);

OutputLatencySource outputLatencySource(const int64_t liveLatencyNs,
                                        const bool liveLatencyValid,
                                        const int64_t sessionConfiguredLatencyNs);

/** Mix timeline frame for PCM submitted at [callbackMonotonicNs] (heard ≈ transport now). */
int64_t mixTransportFrameAtCallback(const int64_t callbackMonotonicNs,
                                    const int64_t effectiveLatencyNs,
                                    const transport_clock::TransportClockAnchor &anchor,
                                    const int64_t offlineTransportFrameFallback,
                                    const int32_t sampleRateHz);

/** Refresh [outLiveLatencyNs] / [outLiveLatencyValid] from [stream->calculateLatencyMillis]. */
void refreshLiveOutputLatencyFromStream(oboe::AudioStream *stream,
                                        int64_t &outLiveLatencyNs,
                                        bool &outLiveLatencyValid);

} // namespace output_render_ahead
