#pragma once

#include <cstdint>

namespace transport_clock {

/** CLOCK_MONOTONIC nanoseconds since boot (shared live time base). */
int64_t monotonicNowNs();

struct TransportClockAnchor {
    int64_t transportStartFrame = 0;
    int64_t monotonicStartNs = 0;
    int32_t sampleRateHz = 0;

    bool isValid() const {
        return sampleRateHz > 0 && monotonicStartNs > 0;
    }

    int64_t transportFrameAt(int64_t monotonicNs) const;

    int64_t transportMsAt(int64_t monotonicNs) const;

    int64_t monotonicNsForTransportFrame(int64_t frame) const;

    /** Timeline ms offset from [transportStartFrame]; inverse of [transportMsAt]. */
    int64_t monotonicNsForTransportMs(int64_t transportMsFromStartFrame) const;
};

} // namespace transport_clock
