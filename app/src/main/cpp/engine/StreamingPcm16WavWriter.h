#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <string>

namespace dawengine {
namespace playback {

/** 16-bit PCM WAV that publishes a valid header after every accepted chunk. */
class StreamingPcm16WavWriter {
public:
    bool Open(const std::string &path, int32_t sampleRateHz, int32_t channelCount) {
        if (path.empty() || sampleRateHz <= 0) return false;
        file_ = std::fopen(path.c_str(), "wb");
        if (!file_) return false;
        sampleRateHz_ = sampleRateHz;
        channelCount_ = static_cast<uint16_t>(std::max(1, channelCount));
        const uint32_t bytesPerSample = kWavBitsPerSample / 8u;
        const uint32_t byteRate =
            static_cast<uint32_t>(sampleRateHz_) * channelCount_ * bytesPerSample;
        const uint16_t blockAlign =
            static_cast<uint16_t>(channelCount_ * bytesPerSample);

        std::fwrite("RIFF", 1, 4, file_);
        WriteUint32LE(36u);
        std::fwrite("WAVE", 1, 4, file_);
        std::fwrite("fmt ", 1, 4, file_);
        WriteUint32LE(16u);
        WriteUint16LE(1u);
        WriteUint16LE(channelCount_);
        WriteUint32LE(static_cast<uint32_t>(sampleRateHz_));
        WriteUint32LE(byteRate);
        WriteUint16LE(blockAlign);
        WriteUint16LE(kWavBitsPerSample);
        std::fwrite("data", 1, 4, file_);
        WriteUint32LE(0u);
        dataBytesWritten_ = 0;
        headerCommitted_ = false;
        return std::ferror(file_) == 0;
    }

    bool WriteFloatInterleaved(const float *samples, std::size_t sampleCount) {
        if (!file_ || samples == nullptr) return false;
        const uint64_t addBytes =
            static_cast<uint64_t>(sampleCount) * sizeof(int16_t);
        if (static_cast<uint64_t>(dataBytesWritten_) + addBytes >
            std::numeric_limits<uint32_t>::max()) {
            return false;
        }
        for (std::size_t i = 0; i < sampleCount; ++i) {
            const int16_t pcm16 = FloatToPcm16(samples[i]);
            if (std::fwrite(&pcm16, sizeof(pcm16), 1, file_) != 1u) {
                return false;
            }
        }
        dataBytesWritten_ += static_cast<uint32_t>(addBytes);
        return std::ferror(file_) == 0;
    }

    /** Flush accepted PCM and rewrite the RIFF/data sizes without closing. */
    bool CommitDurableHeader() {
        if (!file_) return false;
        std::clearerr(file_);
        if (std::fflush(file_) != 0) return false;
        const long endPos = std::ftell(file_);
        if (endPos < 0) return false;
        if (std::fseek(file_, 4, SEEK_SET) != 0) return false;
        WriteUint32LE(36u + dataBytesWritten_);
        if (std::fseek(file_, 40, SEEK_SET) != 0) return false;
        WriteUint32LE(dataBytesWritten_);
        if (std::fflush(file_) != 0) return false;
        if (std::fseek(file_, endPos, SEEK_SET) != 0) return false;
        if (std::ferror(file_) != 0) return false;
        headerCommitted_ = true;
        return true;
    }

    bool Close() {
        if (!file_) return false;
        const bool committed = CommitDurableHeader();
        std::fclose(file_);
        file_ = nullptr;
        return committed;
    }

    bool Finalize() {
        if (!Close()) return false;
        return dataBytesWritten_ > 0u;
    }

    void Abort() {
        if (file_) {
            std::fclose(file_);
            file_ = nullptr;
        }
        dataBytesWritten_ = 0;
        headerCommitted_ = false;
    }

    uint32_t dataBytesWritten() const { return dataBytesWritten_; }

private:
    static constexpr uint16_t kWavBitsPerSample = 16;

    void WriteUint16LE(uint16_t value) {
        const uint8_t bytes[2] = {
            static_cast<uint8_t>(value & 0xFFu),
            static_cast<uint8_t>((value >> 8u) & 0xFFu),
        };
        std::fwrite(bytes, 1, 2, file_);
    }

    void WriteUint32LE(uint32_t value) {
        const uint8_t bytes[4] = {
            static_cast<uint8_t>(value & 0xFFu),
            static_cast<uint8_t>((value >> 8u) & 0xFFu),
            static_cast<uint8_t>((value >> 16u) & 0xFFu),
            static_cast<uint8_t>((value >> 24u) & 0xFFu),
        };
        std::fwrite(bytes, 1, 4, file_);
    }

    static int16_t FloatToPcm16(float sample) {
        const float clamped = std::max(-1.0f, std::min(1.0f, sample));
        return static_cast<int16_t>(clamped * 32767.0f);
    }

    FILE *file_ = nullptr;
    uint32_t dataBytesWritten_ = 0;
    bool headerCommitted_ = false;
    int32_t sampleRateHz_ = 44'100;
    uint16_t channelCount_ = 2;
};

} // namespace playback
} // namespace dawengine
