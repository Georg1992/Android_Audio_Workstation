#pragma once

#include <algorithm>
#include <array>
#include <cstdint>
#include <limits>
#include <mutex>

namespace dawengine {

class DurationUsStats {
public:
    static constexpr std::size_t kWindowSize = 256;

    struct Summary {
        int64_t sampleCount = 0;
        int64_t minUs = 0;
        int64_t maxUs = 0;
        int64_t avgUs = 0;
        int64_t p95Us = 0;
    };

    void record(int64_t durationUs) {
        if (durationUs < 0) {
            return;
        }
        std::lock_guard<std::mutex> lock(mutex_);
        window_[windowIndex_] = durationUs;
        windowIndex_ = (windowIndex_ + 1) % kWindowSize;
        if (filledCount_ < kWindowSize) {
            ++filledCount_;
        }
        ++sampleCount_;
        sumUs_ += durationUs;
        if (durationUs < minUs_) {
            minUs_ = durationUs;
        }
        if (durationUs > maxUs_) {
            maxUs_ = durationUs;
        }
    }

    Summary snapshot() const {
        std::lock_guard<std::mutex> lock(mutex_);
        Summary summary;
        summary.sampleCount = sampleCount_;
        if (sampleCount_ <= 0) {
            return summary;
        }
        summary.minUs = minUs_;
        summary.maxUs = maxUs_;
        summary.avgUs = sumUs_ / static_cast<int64_t>(sampleCount_);
        summary.p95Us = computeP95Locked();
        return summary;
    }

private:
    int64_t computeP95Locked() const {
        if (filledCount_ == 0) {
            return 0;
        }
        std::array<int64_t, kWindowSize> sorted{};
        for (std::size_t i = 0; i < filledCount_; ++i) {
            sorted[i] = window_[i];
        }
        std::sort(sorted.begin(), sorted.begin() + static_cast<std::ptrdiff_t>(filledCount_));
        const std::size_t index =
            (filledCount_ * 95 + 99) / 100 - 1;
        return sorted[std::min(index, filledCount_ - 1)];
    }

    mutable std::mutex mutex_;
    std::array<int64_t, kWindowSize> window_{};
    std::size_t windowIndex_ = 0;
    std::size_t filledCount_ = 0;
    int64_t sampleCount_ = 0;
    int64_t sumUs_ = 0;
    int64_t minUs_ = std::numeric_limits<int64_t>::max();
    int64_t maxUs_ = 0;
};

} // namespace dawengine
