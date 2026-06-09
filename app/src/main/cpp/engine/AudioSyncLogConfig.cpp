#include "AudioSyncLogConfig.h"

#include <atomic>

namespace audio_sync_log_config {
namespace {

std::atomic<bool> g_clockValidationMode{true};
std::atomic<bool> g_detailedStartupLogsEnabled{false};
std::atomic<bool> g_rawTimestampSpamEnabled{false};
std::atomic<bool> g_transportFrameVerboseEnabled{false};

} // namespace

void syncFromJvm(const bool clockValidationMode,
                 const bool detailedStartupLogsEnabled,
                 const bool rawTimestampSpamEnabled,
                 const bool transportFrameVerboseEnabled) {
    g_clockValidationMode.store(clockValidationMode, std::memory_order_release);
    g_detailedStartupLogsEnabled.store(detailedStartupLogsEnabled, std::memory_order_release);
    g_rawTimestampSpamEnabled.store(rawTimestampSpamEnabled, std::memory_order_release);
    g_transportFrameVerboseEnabled.store(transportFrameVerboseEnabled, std::memory_order_release);
}

bool clockValidationMode() {
    return g_clockValidationMode.load(std::memory_order_acquire);
}

bool detailedStartupLogsEnabled() {
    return g_detailedStartupLogsEnabled.load(std::memory_order_acquire);
}

bool rawTimestampSpamEnabled() {
    return g_rawTimestampSpamEnabled.load(std::memory_order_acquire);
}

bool transportFrameVerboseEnabled() {
    return g_transportFrameVerboseEnabled.load(std::memory_order_acquire);
}

bool shouldLogPlaybackLatency() {
    return !clockValidationMode() || rawTimestampSpamEnabled();
}

bool shouldLogOverdubStartupOptimization() {
    return !clockValidationMode() || rawTimestampSpamEnabled();
}

int32_t inputCaptureCorrelationInitialBurstCount() {
    return clockValidationMode() ? 1 : 10;
}

int32_t outputClockCorrelationInitialBurstCount() {
    return clockValidationMode() ? 3 : 10;
}

} // namespace audio_sync_log_config
