#include "OverdubStartupOptimization.h"

#include "AudioEngine.h"

#include <android/log.h>

#include <chrono>
#include <cstring>

namespace overdub_startup_opt {
namespace {

constexpr const char *kLogTag = "AudioSyncDiag";

int64_t steadyClockNowNs() {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(
               std::chrono::steady_clock::now().time_since_epoch())
        .count();
}

long long deltaMsFromArm(const int64_t armNs, const int64_t stageNs) {
    if (armNs <= 0 || stageNs <= armNs) {
        return -1;
    }
    return static_cast<long long>((stageNs - armNs) / 1'000'000LL);
}

} // namespace

void logStartupTiming(const dawengine::AudioEngine *const engine, const char *const trigger) {
    if (!engine || !trigger) {
        return;
    }

    const dawengine::AudioEngine::PlaybackSessionTimings timings =
        engine->playbackSessionTimings();
    const int64_t armNs = timings.playbackArmSteadyNs;
    const int64_t jniReadyNs = engine->overdubJniReadySteadyNs();
    const int64_t nowNs = steadyClockNowNs();

    const long long outputEnsureStartMs =
        deltaMsFromArm(armNs, timings.oboeStreamStartDoneSteadyNs);
    const long long inputOpenMs = deltaMsFromArm(armNs, timings.openInputDoneSteadyNs);
    const long long firstOutputCallbackMs =
        deltaMsFromArm(armNs, timings.firstOboeCallbackSteadyNs);
    const long long firstInputMs =
        deltaMsFromArm(armNs, timings.firstInputSampleSteadyNs);
    const long long deferredGateReleaseMs =
        timings.deferEnabled != 0 ? firstInputMs : -1L;
    const long long totalArmToReadyMs =
        std::strcmp(trigger, "deferred_gate_released") == 0
            ? (firstInputMs >= 0 ? firstInputMs : deltaMsFromArm(armNs, nowNs))
            : (jniReadyNs > 0 ? deltaMsFromArm(armNs, jniReadyNs)
                              : deltaMsFromArm(armNs, nowNs));

    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "[OVERDUB_STARTUP_TIMING] "
        "trigger=%s "
        "playbackArmMs=0 "
        "outputEnsureStartMs=%lld "
        "inputOpenMs=%lld "
        "firstOutputCallbackMs=%lld "
        "firstInputMs=%lld "
        "deferredGateReleaseMs=%lld "
        "totalArmToReadyMs=%lld",
        trigger,
        outputEnsureStartMs,
        inputOpenMs,
        firstOutputCallbackMs,
        firstInputMs,
        deferredGateReleaseMs,
        totalArmToReadyMs);
}

} // namespace overdub_startup_opt
