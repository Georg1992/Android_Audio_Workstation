#pragma once

#include "TransportClockAnchor.h"

namespace dawengine {
class AudioEngine;
} // namespace dawengine

namespace oboe {
class AudioStream;
} // namespace oboe

namespace transport_clock_diag {

void resetTransportClockDiagnostics();

void logTransportClockAnchor(const transport_clock::TransportClockAnchor &anchor,
                             const char *reason);

void logPlacementClockComparison(dawengine::AudioEngine *engine,
                                 const char *mode,
                                 int64_t legacyFirstSampleTransportFrame,
                                 int64_t appReceiveMonotonicNs);

int64_t lastPlacementClockDeltaMs();

void maybeLogOutputClockCorrelation(dawengine::AudioEngine *engine,
                                    oboe::AudioStream *stream,
                                    int64_t callbackMonotonicNs,
                                    int32_t numFrames);

void maybeLogInputCaptureCorrelation(dawengine::AudioEngine *engine,
                                     int64_t bufferIndex,
                                     int64_t appReceiveMonotonicNs,
                                     int32_t readFrames);

} // namespace transport_clock_diag
