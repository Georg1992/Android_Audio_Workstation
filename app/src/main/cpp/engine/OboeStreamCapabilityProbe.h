#pragma once

#include <oboe/Oboe.h>
#include <cstdint>

namespace oboe_capability {

struct StreamCapabilityProbe {
    int32_t sampleRateHz = 0;
    int32_t channelCount = 0;
    int32_t framesPerBurst = 0;
    int32_t bufferCapacityInFrames = 0;
    int32_t bufferSizeInFrames = 0;
    int32_t performanceModeActual = 0;
    int32_t sharingModeActual = 0;
    int32_t audioSessionId = 0;
    int32_t audioApi = 0;
    int32_t format = 0;
    double estimatedStreamLatencyMs = -1.0;
    int32_t timestampAvailable = 0;
    int32_t timestampStable = 0;
    int32_t blockFrames = 0;
    int32_t xRunCount = 0;
};

/** Read-only probe of an open Oboe stream; does not mutate stream configuration. */
StreamCapabilityProbe probeStream(oboe::AudioStream *stream, int32_t blockFrames);

} // namespace oboe_capability
