#pragma once

#include <cstdint>

namespace audio_sync_log_config {

void syncFromJvm(bool clockValidationMode,
                 bool detailedStartupLogsEnabled,
                 bool rawTimestampSpamEnabled,
                 bool transportFrameVerboseEnabled);

bool clockValidationMode();
bool detailedStartupLogsEnabled();
bool rawTimestampSpamEnabled();
bool transportFrameVerboseEnabled();

bool shouldLogPlaybackLatency();
bool shouldLogOverdubStartupOptimization();

int32_t inputCaptureCorrelationInitialBurstCount();
int32_t outputClockCorrelationInitialBurstCount();

} // namespace audio_sync_log_config
