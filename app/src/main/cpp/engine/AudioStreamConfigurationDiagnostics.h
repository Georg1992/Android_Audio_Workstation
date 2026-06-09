#pragma once

#include <oboe/Oboe.h>

#include <cstdint>

namespace audio_stream_config_diag {

struct StreamOpenRequest {
    oboe::AudioApi audioApi = oboe::AudioApi::Unspecified;
    oboe::PerformanceMode performanceMode = oboe::PerformanceMode::None;
    oboe::SharingMode sharingMode = oboe::SharingMode::Shared;
    int32_t sampleRateHz = 0;
    int32_t channelCount = 0;
    /** 0 = builder did not call setBufferSizeInFrames. */
    int32_t bufferSizeInFrames = 0;
};

void logOpenedStream(const char *streamRole,
                     const StreamOpenRequest &requested,
                     oboe::AudioStream *stream,
                     oboe::Result openResult);

/** HAL/driver playback floor; excludes app ring, startup, capture. */
void logOutputHardwareFloor(const char *context,
                            const StreamOpenRequest &requested,
                            oboe::AudioStream *stream);

void resetOutputHardwareFloorLogged();

/** Log once when [calculateLatencyMillis] returns a sane value after stream start. */
void maybeLogOutputHardwareFloorOnce(const char *context,
                                     const StreamOpenRequest &requested,
                                     oboe::AudioStream *stream);

} // namespace audio_stream_config_diag
