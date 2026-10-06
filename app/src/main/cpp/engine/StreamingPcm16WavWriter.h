#pragma once

#include <algorithm>
#include <cstdint>
#include <cstdio>
#include <limits>
#include <string>
#include <vector>

namespace dawengine {

/**
 * Canonical 44-byte 16-bit PCM WAV used by take commit and offline mixdown.
 * Header sizes live at [kRiffSizeOffset] and [kDataSizeOffset].
 */
class StreamingPcm16WavWriter {
public:
    static constexpr int32_t kBitsPerSample = 16;
    static constexpr long kRiffSizeOffset = 4;
    static constexpr long kDataSizeOffset = 40;
    static constexpr uint32_t kRiffPayloadPrefix = 36u;

    bool Open(const std::string &path,
              int32_t sampleRateHz,
              int32_t channelCount,
              int32_t bitDepth) {
        if (path.empty() || sampleRateHz <= 0 || bitDepth != kBitsPerSample) return false;
        file_ = std::fopen(path.c_str(), "wb");
        if (!file_) return false;
        sampleRateHz_ = sampleRateHz;
        channelCount_ = static_cast<uint16_t>(std::max(1, channelCount));
        const uint32_t bytesPerSample = static_cast<uint32_t>(kBitsPerSample / 8);
        const uint32_t byteRate =
            static_cast<uint32_t>(sampleRateHz_) * channelCount_ * bytesPerSample;
        const uint16_t blockAlign =
            static_cast<uint16_t>(channelCount_ * bytesPerSample);

        std::fwrite("RIFF", 1, 4, file_);
        WriteUint32LE(kRiffPayloadPrefix);
        std::fwrite("WAVE", 1, 4, file_);
        std::fwrite("fmt ", 1, 4, file_);
        WriteUint32LE(16u);
        WriteUint16LE(1u);
        WriteUint16LE(channelCount_);
        WriteUint32LE(static_cast<uint32_t>(sampleRateHz_));
        WriteUint32LE(byteRate);
        WriteUint16LE(blockAlign);
        WriteUint16LE(static_cast<uint16_t>(kBitsPerSample));
        std::fwrite("data", 1, 4, file_);
        WriteUint32LE(0u);
        dataBytesWritten_ = 0;
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
        std::vector<uint8_t> pcm(sampleCount * sizeof(int16_t));
        for (std::size_t i = 0; i < sampleCount; ++i) {
            const int16_t pcm16 = FloatToPcm16(samples[i]);
            pcm[i * 2u] = static_cast<uint8_t>(static_cast<uint16_t>(pcm16) & 0xFFu);
            pcm[i * 2u + 1u] =
                static_cast<uint8_t>((static_cast<uint16_t>(pcm16) >> 8u) & 0xFFu);
        }
        if (std::fwrite(pcm.data(), 1, pcm.size(), file_) != pcm.size()) {
            return false;
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
        if (std::fseek(file_, kRiffSizeOffset, SEEK_SET) != 0) return false;
        WriteUint32LE(kRiffPayloadPrefix + dataBytesWritten_);
        if (std::fseek(file_, kDataSizeOffset, SEEK_SET) != 0) return false;
        WriteUint32LE(dataBytesWritten_);
        if (std::fflush(file_) != 0) return false;
        if (std::fseek(file_, endPos, SEEK_SET) != 0) return false;
        return std::ferror(file_) == 0;
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
    }

    uint32_t dataBytesWritten() const { return dataBytesWritten_; }

private:
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
    int32_t sampleRateHz_ = 44'100;
    uint16_t channelCount_ = 2;
};

} // namespace dawengine
