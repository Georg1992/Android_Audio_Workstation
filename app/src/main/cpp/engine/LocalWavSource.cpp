#include "LocalWavSource.h"

#include <algorithm>
#include <array>
#include <cstring>
#include <utility>
#include <vector>

namespace dawengine {

namespace {

constexpr uint16_t kPcmFormat = 1u;
constexpr uint16_t kSupportedBitsPerSample = 16u;

bool ReadUint16LE(std::FILE *file, uint16_t *out) {
    std::array<uint8_t, 2> bytes{};
    if (std::fread(bytes.data(), 1, bytes.size(), file) != bytes.size()) return false;
    *out = static_cast<uint16_t>(bytes[0] | (bytes[1] << 8u));
    return true;
}

bool ReadUint32LE(std::FILE *file, uint32_t *out) {
    std::array<uint8_t, 4> bytes{};
    if (std::fread(bytes.data(), 1, bytes.size(), file) != bytes.size()) return false;
    *out = static_cast<uint32_t>(bytes[0] |
                                 (bytes[1] << 8u) |
                                 (bytes[2] << 16u) |
                                 (bytes[3] << 24u));
    return true;
}

} // namespace

LocalWavSource::LocalWavSource(std::string path) : m_path(std::move(path)) {}

LocalWavSource::~LocalWavSource() {
    if (m_file) {
        std::fclose(m_file);
        m_file = nullptr;
    }
}

bool LocalWavSource::open() {
    if (m_file || m_path.empty()) return false;

    std::FILE *file = std::fopen(m_path.c_str(), "rb");
    if (!file) return false;

    char riff[4];
    char wave[4];
    uint32_t riffSize = 0;
    if (std::fread(riff, 1, sizeof(riff), file) != sizeof(riff) ||
        !ReadUint32LE(file, &riffSize) ||
        std::fread(wave, 1, sizeof(wave), file) != sizeof(wave) ||
        std::memcmp(riff, "RIFF", 4) != 0 ||
        std::memcmp(wave, "WAVE", 4) != 0) {
        std::fclose(file);
        return false;
    }

    uint16_t audioFormat = 0;
    uint16_t channelCount = 0;
    uint32_t sampleRate = 0;
    uint16_t bitsPerSample = 0;
    bool fmtFound = false;
    long dataOffset = 0;
    uint32_t dataSize = 0;
    bool dataFound = false;

    while (!fmtFound || !dataFound) {
        char chunkId[4];
        uint32_t chunkSize = 0;
        if (std::fread(chunkId, 1, sizeof(chunkId), file) != sizeof(chunkId) ||
            !ReadUint32LE(file, &chunkSize)) {
            break;
        }

        if (std::memcmp(chunkId, "fmt ", 4) == 0) {
            uint16_t blockAlign = 0;
            uint32_t byteRate = 0;
            if (!ReadUint16LE(file, &audioFormat) ||
                !ReadUint16LE(file, &channelCount) ||
                !ReadUint32LE(file, &sampleRate) ||
                !ReadUint32LE(file, &byteRate) ||
                !ReadUint16LE(file, &blockAlign) ||
                !ReadUint16LE(file, &bitsPerSample)) {
                break;
            }
            const long fmtRemaining = static_cast<long>(chunkSize) - 16L;
            if (fmtRemaining > 0) {
                std::fseek(file, fmtRemaining, SEEK_CUR);
            }
            fmtFound = true;
        } else if (std::memcmp(chunkId, "data", 4) == 0) {
            dataOffset = std::ftell(file);
            dataSize = chunkSize;
            dataFound = true;
            // Don't read the payload — we stream it in [readFrames].
        } else {
            std::fseek(file, static_cast<long>(chunkSize), SEEK_CUR);
        }

        if ((chunkSize & 1u) != 0u) {
            std::fseek(file, 1L, SEEK_CUR);
        }
    }

    if (!fmtFound || !dataFound ||
        audioFormat != kPcmFormat ||
        bitsPerSample != kSupportedBitsPerSample ||
        channelCount == 0 || channelCount > 2) {
        std::fclose(file);
        return false;
    }

    const uint32_t bytesPerFrame = channelCount * (bitsPerSample / 8u);
    if (bytesPerFrame == 0) {
        std::fclose(file);
        return false;
    }

    // Position at the first audio frame so [readFrames] can start streaming.
    if (std::fseek(file, dataOffset, SEEK_SET) != 0) {
        std::fclose(file);
        return false;
    }

    m_file = file;
    m_dataChunkOffset = dataOffset;
    m_sampleRate = static_cast<int32_t>(sampleRate);
    m_channelCount = static_cast<int32_t>(channelCount);
    m_totalFrames = static_cast<int64_t>(dataSize) / static_cast<int64_t>(bytesPerFrame);
    m_currentFrame = 0;
    return true;
}

int32_t LocalWavSource::readFrames(float *dst, int32_t frames) {
    if (!m_file || !dst || frames <= 0 || m_channelCount <= 0) return -1;

    const int32_t framesRemaining = static_cast<int32_t>(
        std::max<int64_t>(0, m_totalFrames - m_currentFrame)
    );
    const int32_t framesToRead = std::min(frames, framesRemaining);
    if (framesToRead == 0) return 0;

    const std::size_t sampleCount = static_cast<std::size_t>(framesToRead) *
                                    static_cast<std::size_t>(m_channelCount);

    // Pull int16 PCM and convert to float in one pass. Using a thread-local
    // staging buffer keeps the file I/O off the audio thread and avoids
    // per-call heap allocation in the I/O worker.
    static thread_local std::vector<int16_t> scratch;
    if (scratch.size() < sampleCount) {
        scratch.resize(sampleCount);
    }

    const std::size_t samplesRead = std::fread(scratch.data(), sizeof(int16_t), sampleCount, m_file);
    if (samplesRead == 0) {
        return std::ferror(m_file) != 0 ? -1 : 0;
    }
    const int32_t framesActuallyRead = static_cast<int32_t>(samplesRead / static_cast<std::size_t>(m_channelCount));

    constexpr float kInvInt16Scale = 1.0f / 32768.0f;
    for (std::size_t i = 0; i < samplesRead; ++i) {
        dst[i] = static_cast<float>(scratch[i]) * kInvInt16Scale;
    }

    m_currentFrame += framesActuallyRead;
    return framesActuallyRead;
}

bool LocalWavSource::seekToFrame(int64_t framePosition) {
    if (!m_file || framePosition < 0 || framePosition > m_totalFrames) return false;

    const int32_t bytesPerFrame = m_channelCount * static_cast<int32_t>(sizeof(int16_t));
    const long target = m_dataChunkOffset + static_cast<long>(framePosition * bytesPerFrame);
    if (std::fseek(m_file, target, SEEK_SET) != 0) return false;

    m_currentFrame = framePosition;
    return true;
}

} // namespace dawengine
