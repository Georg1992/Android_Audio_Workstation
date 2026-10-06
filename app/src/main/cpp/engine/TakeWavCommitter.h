#pragma once

#include "StreamingPcm16WavWriter.h"

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <mutex>
#include <string>
#include <thread>
#include <utility>
#include <vector>

namespace dawengine {

/**
 * Queues take PCM on the capture thread and commits the shared 16-bit WAV on a writer thread.
 */
class TakeWavCommitter {
public:
    static constexpr std::size_t kMaxQueuedChunks = 32;

    TakeWavCommitter() = default;
    ~TakeWavCommitter() {
        if (thread_.joinable()) {
            Finish(true);
        }
    }

    TakeWavCommitter(const TakeWavCommitter &) = delete;
    TakeWavCommitter &operator=(const TakeWavCommitter &) = delete;

    bool Open(const std::string &path,
              int32_t sampleRateHz,
              int32_t channelCount,
              int32_t bitDepth,
              std::atomic<bool> *captureFailed) {
        if (!writer_.Open(path, sampleRateHz, channelCount, bitDepth)) return false;
        if (!writer_.CommitDurableHeader()) {
            writer_.Abort();
            std::remove(path.c_str());
            return false;
        }
        path_ = path;
        captureFailed_ = captureFailed;
        open_ = true;
        thread_ = std::thread(&TakeWavCommitter::ThreadMain, this);
        return true;
    }

    /** Capture thread only. False when the writer has failed or the queue is full. */
    bool Enqueue(const float *samples, std::size_t sampleCount) {
        if (sampleCount == 0) return true;
        if (samples == nullptr) return false;
        std::lock_guard<std::mutex> lock(mutex_);
        if (writeFailed_ || stopRequested_) return false;
        if (pending_.size() >= kMaxQueuedChunks) return false;
        pending_.emplace_back(samples, samples + sampleCount);
        cv_.notify_one();
        return true;
    }

    /**
     * Joins the writer thread. [discard] drops the file.
     * A writer-thread failure also drops the file.
     */
    bool Finish(bool discard) {
        {
            std::lock_guard<std::mutex> lock(mutex_);
            if (discard) {
                pending_.clear();
            }
            stopRequested_ = true;
        }
        cv_.notify_all();
        if (thread_.joinable()) {
            thread_.join();
        }
        if (!open_) return false;
        const bool failed =
            discard || writeFailed_ ||
            (captureFailed_ != nullptr &&
             captureFailed_->load(std::memory_order_acquire));
        open_ = false;
        if (failed) {
            writer_.Abort();
            if (!path_.empty()) {
                std::remove(path_.c_str());
            }
            path_.clear();
            return false;
        }
        const bool sealed = writer_.Close();
        path_.clear();
        return sealed;
    }

    uint32_t dataBytesWritten() const { return writer_.dataBytesWritten(); }

private:
    void ThreadMain() {
        for (;;) {
            std::vector<float> chunk;
            {
                std::unique_lock<std::mutex> lock(mutex_);
                cv_.wait(lock, [&] { return stopRequested_ || !pending_.empty(); });
                if (pending_.empty()) return;
                chunk = std::move(pending_.front());
                pending_.pop_front();
            }
            if (!writer_.WriteFloatInterleaved(chunk.data(), chunk.size()) ||
                !writer_.CommitDurableHeader()) {
                std::lock_guard<std::mutex> lock(mutex_);
                writeFailed_ = true;
                if (captureFailed_ != nullptr) {
                    captureFailed_->store(true, std::memory_order_release);
                }
                return;
            }
        }
    }

    StreamingPcm16WavWriter writer_;
    std::string path_;
    std::atomic<bool> *captureFailed_ = nullptr;
    std::mutex mutex_;
    std::condition_variable cv_;
    std::deque<std::vector<float>> pending_;
    bool stopRequested_ = false;
    bool writeFailed_ = false;
    bool open_ = false;
    std::thread thread_;
};

} // namespace dawengine
