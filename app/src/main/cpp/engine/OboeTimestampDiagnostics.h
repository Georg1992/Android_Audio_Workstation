#pragma once

#include <oboe/Oboe.h>
#include <cstdint>

/**
 * Read-only Oboe/AAudio timestamp diagnostics for AudioSyncDiag investigation.
 * Does not affect audio behavior, transport, or compensation.
 */
namespace oboe_timestamp_diag {

/** Reset output-side throttle (call when the output stream starts or resumes). */
void resetOutputTimestampDiagnostics();

/** Reset input-side throttle (call when the input stream opens). */
void resetInputTimestampDiagnostics();

/**
 * Records a timestamp sample for stability tracking.
 * @return true when position and timestamp both advance coherently across samples.
 */
bool recordTimestampSampleAndCheckStable(oboe::AudioStream *stream,
                                         int64_t framePosition,
                                         int64_t timestampNs);

/**
 * Log [OUTPUT_TIMESTAMP] when throttle allows.
 * @param transportFrame Transport frame at block start (pre-render).
 * @param masterPlaybackFrame Same master playback counter at block start.
 */
void maybeLogOutputTimestamp(oboe::AudioStream *stream,
                             int64_t transportFrame,
                             int64_t masterPlaybackFrame,
                             int32_t callbackFrames);

/**
 * Log [INPUT_TIMESTAMP] when throttle allows.
 * @param transportFrameAtRead Master playback / transport frame when the read completed.
 */
void maybeLogInputTimestamp(oboe::AudioStream *stream,
                            int64_t transportFrameAtRead,
                            int32_t readFrames);

} // namespace oboe_timestamp_diag
