#include "TransportClockAnchor.h"

#include <algorithm>
#include <time.h>

namespace transport_clock {

int64_t monotonicNowNs() {
    timespec ts{};
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) {
        return 0;
    }
    return static_cast<int64_t>(ts.tv_sec) * 1'000'000'000LL +
           static_cast<int64_t>(ts.tv_nsec);
}

int64_t TransportClockAnchor::transportFrameAt(const int64_t monotonicNs) const {
    if (sampleRateHz <= 0 || monotonicStartNs <= 0) {
        return transportStartFrame;
    }
    const int64_t elapsedNs = monotonicNs - monotonicStartNs;
    const int64_t frameDelta =
        (elapsedNs * static_cast<int64_t>(sampleRateHz)) / 1'000'000'000LL;
    return transportStartFrame + frameDelta;
}

int64_t TransportClockAnchor::transportMsAt(const int64_t monotonicNs) const {
    if (sampleRateHz <= 0) {
        return 0;
    }
    const int64_t frame = transportFrameAt(monotonicNs) - transportStartFrame;
    return (std::max<int64_t>(0, frame) * 1000LL) / static_cast<int64_t>(sampleRateHz);
}

int64_t TransportClockAnchor::monotonicNsForTransportFrame(const int64_t frame) const {
    if (sampleRateHz <= 0 || monotonicStartNs <= 0) {
        return monotonicStartNs;
    }
    const int64_t frameDelta = frame - transportStartFrame;
    const int64_t nsDelta =
        (frameDelta * 1'000'000'000LL) / static_cast<int64_t>(sampleRateHz);
    return monotonicStartNs + nsDelta;
}

int64_t TransportClockAnchor::monotonicNsForTransportMs(
    const int64_t transportMsFromStartFrame) const {
    if (sampleRateHz <= 0) {
        return monotonicStartNs;
    }
    const int64_t frameDelta =
        (std::max<int64_t>(0, transportMsFromStartFrame) *
         static_cast<int64_t>(sampleRateHz)) /
        1000LL;
    return monotonicNsForTransportFrame(transportStartFrame + frameDelta);
}

} // namespace transport_clock
