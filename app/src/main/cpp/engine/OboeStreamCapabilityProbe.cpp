#include "OboeStreamCapabilityProbe.h"

#include "OboeTimestampDiagnostics.h"

#include <time.h>

namespace oboe_capability {
namespace {

void probeTimestamp(oboe::AudioStream *stream,
                    int32_t &timestampAvailable,
                    int32_t &timestampStable) {
    timestampAvailable = 0;
    timestampStable = 0;
    if (!stream) {
        return;
    }

    const auto timestampResult = stream->getTimestamp(CLOCK_MONOTONIC);
    if (!timestampResult) {
        return;
    }

    timestampAvailable = 1;
    timestampStable =
        oboe_timestamp_diag::recordTimestampSampleAndCheckStable(
            stream,
            timestampResult.value().position,
            timestampResult.value().timestamp)
            ? 1
            : 0;
}

} // namespace

StreamCapabilityProbe probeStream(oboe::AudioStream *const stream,
                                  const int32_t blockFrames) {
    StreamCapabilityProbe probe;
    probe.blockFrames = blockFrames;
    if (!stream) {
        return probe;
    }

    probe.sampleRateHz = stream->getSampleRate();
    probe.channelCount = stream->getChannelCount();
    probe.framesPerBurst = stream->getFramesPerBurst();
    probe.bufferCapacityInFrames = stream->getBufferCapacityInFrames();
    probe.bufferSizeInFrames = stream->getBufferSizeInFrames();
    probe.performanceModeActual = static_cast<int32_t>(stream->getPerformanceMode());
    probe.sharingModeActual = static_cast<int32_t>(stream->getSharingMode());
    probe.audioSessionId = static_cast<int32_t>(stream->getSessionId());
    probe.audioApi = static_cast<int32_t>(stream->getAudioApi());
    probe.format = static_cast<int32_t>(stream->getFormat());
    const auto xRunResult = stream->getXRunCount();
    probe.xRunCount = xRunResult ? xRunResult.value() : 0;

    const auto latencyResult = stream->calculateLatencyMillis();
    if (latencyResult) {
        probe.estimatedStreamLatencyMs = latencyResult.value();
    }

    probeTimestamp(stream, probe.timestampAvailable, probe.timestampStable);
    return probe;
}

} // namespace oboe_capability
