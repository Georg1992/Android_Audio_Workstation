#include "AudioEngine.h"

#include <algorithm>
#include <array>
#include <cstdio>
#include <cstring>

namespace {

constexpr int64_t kReadTimeoutNanos = 100 * oboe::kNanosPerMillisecond;
constexpr int32_t kFramesPerRead = 256;
constexpr uint16_t kWavBitsPerSample = 16;

void WriteUint16LE(FILE *file, uint16_t value) {
    const std::array<uint8_t, 2> bytes = {
        static_cast<uint8_t>(value & 0xFFu),
        static_cast<uint8_t>((value >> 8u) & 0xFFu)
    };
    std::fwrite(bytes.data(), 1, bytes.size(), file);
}

void WriteUint32LE(FILE *file, uint32_t value) {
    const std::array<uint8_t, 4> bytes = {
        static_cast<uint8_t>(value & 0xFFu),
        static_cast<uint8_t>((value >> 8u) & 0xFFu),
        static_cast<uint8_t>((value >> 16u) & 0xFFu),
        static_cast<uint8_t>((value >> 24u) & 0xFFu)
    };
    std::fwrite(bytes.data(), 1, bytes.size(), file);
}

bool ReadUint16LE(FILE *file, uint16_t *value) {
    std::array<uint8_t, 2> bytes{};
    if (std::fread(bytes.data(), 1, bytes.size(), file) != bytes.size()) return false;
    *value = static_cast<uint16_t>(bytes[0] | (bytes[1] << 8u));
    return true;
}

bool ReadUint32LE(FILE *file, uint32_t *value) {
    std::array<uint8_t, 4> bytes{};
    if (std::fread(bytes.data(), 1, bytes.size(), file) != bytes.size()) return false;
    *value = static_cast<uint32_t>(bytes[0] |
                                   (bytes[1] << 8u) |
                                   (bytes[2] << 16u) |
                                   (bytes[3] << 24u));
    return true;
}

int16_t FloatToPcm16(float sample) {
    const float clamped = std::max(-1.0f, std::min(1.0f, sample));
    return static_cast<int16_t>(clamped * 32767.0f);
}

float ReadPlaybackSample(const std::vector<float> &samples,
                         size_t frameIndex,
                         int32_t sourceChannels,
                         int32_t outputChannel) {
    const size_t baseIndex = frameIndex * static_cast<size_t>(sourceChannels);
    if (sourceChannels == 1) {
        return samples[baseIndex];
    }
    const int32_t sourceChannel = std::min(outputChannel, sourceChannels - 1);
    return samples[baseIndex + static_cast<size_t>(sourceChannel)];
}

} // namespace

namespace dawengine {

AudioEngine::AudioEngine() = default;

AudioEngine::~AudioEngine() {
    stopPlayback();
    stopRecording();
}

void AudioEngine::configureProject(int32_t sampleRate, int32_t fileBitDepth) {
    m_sampleRate = sampleRate;
    m_fileBitDepth = fileBitDepth;
}

void AudioEngine::clearTracks() {
    std::lock_guard<std::mutex> trackLock(m_trackMutex);
    m_tracks.clear();
    stopPlayback();
}

void AudioEngine::addTrack(const std::string &wavPath) {
    if (wavPath.empty()) return;
    std::lock_guard<std::mutex> trackLock(m_trackMutex);
    m_tracks.push_back(Track{wavPath});
}

bool AudioEngine::openInputStream(int32_t channelCount) {
    oboe::AudioStreamBuilder builder;
    builder.setDirection(oboe::Direction::Input);
    builder.setFormat(oboe::AudioFormat::Float);
    builder.setSampleRate(m_sampleRate);
    builder.setChannelCount(channelCount);
    builder.setSharingMode(oboe::SharingMode::Shared);
    builder.setPerformanceMode(oboe::PerformanceMode::LowLatency);

    std::shared_ptr<oboe::AudioStream> stream;
    const oboe::Result openResult = builder.openStream(stream);
    if (openResult != oboe::Result::OK || !stream) return false;
    if (stream->requestStart() != oboe::Result::OK) {
        stream->close();
        return false;
    }
    m_inputStream = stream;
    return true;
}

void AudioEngine::closeInputStream() {
    if (!m_inputStream) return;
    m_inputStream->close();
    m_inputStream.reset();
}

bool AudioEngine::startRecording(int32_t channelCount, const std::string &outputPath) {
    if (outputPath.empty() || m_isRecording.exchange(true)) {
        return false;
    }

    {
        std::lock_guard<std::mutex> recordLock(m_recordMutex);
        m_recordedSamples.clear();
        m_recordingOutputPath = outputPath;
        m_recordingChannelCount = channelCount == 2 ? 2 : 1;
    }

    if (!openInputStream(m_recordingChannelCount)) {
        m_isRecording = false;
        return false;
    }

    m_recordThread = std::thread(&AudioEngine::recordLoop, this);
    return true;
}

void AudioEngine::recordLoop() {
    const int32_t channelCount = std::max(1, m_recordingChannelCount);
    std::vector<float> buffer(static_cast<size_t>(kFramesPerRead * channelCount));

    while (m_isRecording) {
        if (!m_inputStream) break;
        const auto result = m_inputStream->read(buffer.data(), kFramesPerRead, kReadTimeoutNanos);
        if (!result) {
            if (result.error() != oboe::Result::ErrorTimeout) {
                break;
            }
            continue;
        }

        const int32_t framesRead = result.value();
        if (framesRead <= 0) continue;

        const size_t sampleCount = static_cast<size_t>(framesRead * channelCount);
        std::lock_guard<std::mutex> recordLock(m_recordMutex);
        m_recordedSamples.insert(
            m_recordedSamples.end(),
            buffer.begin(),
            buffer.begin() + static_cast<std::ptrdiff_t>(sampleCount)
        );
    }
}

bool AudioEngine::writeRecordingToWav(const std::vector<float> &samples,
                                      int32_t channelCount,
                                      const std::string &outputPath) const {
    if (outputPath.empty()) return false;

    FILE *file = std::fopen(outputPath.c_str(), "wb");
    if (!file) return false;

    const uint32_t bytesPerSample = kWavBitsPerSample / 8u;
    const uint32_t dataSize = static_cast<uint32_t>(samples.size() * bytesPerSample);
    const uint16_t wavChannelCount = static_cast<uint16_t>(std::max(1, channelCount));
    const uint32_t byteRate = static_cast<uint32_t>(m_sampleRate) * wavChannelCount * bytesPerSample;
    const uint16_t blockAlign = static_cast<uint16_t>(wavChannelCount * bytesPerSample);

    std::fwrite("RIFF", 1, 4, file);
    WriteUint32LE(file, 36u + dataSize);
    std::fwrite("WAVE", 1, 4, file);
    std::fwrite("fmt ", 1, 4, file);
    WriteUint32LE(file, 16u);
    WriteUint16LE(file, 1u);
    WriteUint16LE(file, wavChannelCount);
    WriteUint32LE(file, static_cast<uint32_t>(m_sampleRate));
    WriteUint32LE(file, byteRate);
    WriteUint16LE(file, blockAlign);
    WriteUint16LE(file, kWavBitsPerSample);
    std::fwrite("data", 1, 4, file);
    WriteUint32LE(file, dataSize);

    for (float sample : samples) {
        const int16_t pcm16 = FloatToPcm16(sample);
        std::fwrite(&pcm16, sizeof(pcm16), 1, file);
    }

    const bool writeOk = std::ferror(file) == 0;
    std::fclose(file);
    return writeOk;
}

bool AudioEngine::stopRecording() {
    if (!m_isRecording.exchange(false)) {
        return false;
    }

    if (m_inputStream) {
        m_inputStream->requestStop();
    }
    if (m_recordThread.joinable()) {
        m_recordThread.join();
    }
    closeInputStream();

    std::vector<float> recordedSamples;
    std::string outputPath;
    int32_t channelCount = 1;
    {
        std::lock_guard<std::mutex> recordLock(m_recordMutex);
        recordedSamples = m_recordedSamples;
        outputPath = m_recordingOutputPath;
        channelCount = m_recordingChannelCount;
        m_recordedSamples.clear();
        m_recordingOutputPath.clear();
    }

    return writeRecordingToWav(recordedSamples, channelCount, outputPath);
}

bool AudioEngine::readWavFile(const std::string &path, LoadedWav *loadedWav) {
    if (!loadedWav || path.empty()) return false;

    FILE *file = std::fopen(path.c_str(), "rb");
    if (!file) return false;

    char riff[4];
    char wave[4];
    [[maybe_unused]] uint32_t chunkSize = 0;
    if (std::fread(riff, 1, sizeof(riff), file) != sizeof(riff) ||
        !ReadUint32LE(file, &chunkSize) ||
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
    std::vector<uint8_t> pcmBytes;
    bool fmtFound = false;
    bool dataFound = false;

    while (!fmtFound || !dataFound) {
        char chunkId[4];
        uint32_t currentChunkSize = 0;
        if (std::fread(chunkId, 1, sizeof(chunkId), file) != sizeof(chunkId) ||
            !ReadUint32LE(file, &currentChunkSize)) {
            break;
        }

        if (std::memcmp(chunkId, "fmt ", 4) == 0) {
            [[maybe_unused]] uint16_t blockAlign = 0;
            [[maybe_unused]] uint32_t byteRate = 0;
            if (!ReadUint16LE(file, &audioFormat) ||
                !ReadUint16LE(file, &channelCount) ||
                !ReadUint32LE(file, &sampleRate) ||
                !ReadUint32LE(file, &byteRate) ||
                !ReadUint16LE(file, &blockAlign) ||
                !ReadUint16LE(file, &bitsPerSample)) {
                break;
            }
            const long remaining = static_cast<long>(currentChunkSize) - 16L;
            if (remaining > 0) {
                std::fseek(file, remaining, SEEK_CUR);
            }
            fmtFound = true;
        } else if (std::memcmp(chunkId, "data", 4) == 0) {
            pcmBytes.resize(currentChunkSize);
            if (currentChunkSize > 0 &&
                std::fread(pcmBytes.data(), 1, currentChunkSize, file) != currentChunkSize) {
                pcmBytes.clear();
                break;
            }
            dataFound = true;
        } else {
            std::fseek(file, static_cast<long>(currentChunkSize), SEEK_CUR);
        }

        if ((currentChunkSize & 1u) != 0u) {
            std::fseek(file, 1L, SEEK_CUR);
        }
    }

    std::fclose(file);

    if (!fmtFound || !dataFound || audioFormat != 1u || bitsPerSample != kWavBitsPerSample || channelCount == 0) {
        return false;
    }

    const size_t sampleCount = pcmBytes.size() / sizeof(int16_t);
    loadedWav->samples.resize(sampleCount);
    for (size_t index = 0; index < sampleCount; ++index) {
        const size_t byteIndex = index * sizeof(int16_t);
        const uint16_t rawValue = static_cast<uint16_t>(
            pcmBytes[byteIndex] | (pcmBytes[byteIndex + 1] << 8u)
        );
        const int16_t pcm16 = static_cast<int16_t>(rawValue);
        loadedWav->samples[index] = static_cast<float>(pcm16) / 32768.0f;
    }
    loadedWav->sampleRate = static_cast<int32_t>(sampleRate);
    loadedWav->channelCount = static_cast<int32_t>(channelCount);
    return !loadedWav->samples.empty();
}

bool AudioEngine::loadPlaybackTrack() {
    std::vector<Track> tracks;
    {
        std::lock_guard<std::mutex> trackLock(m_trackMutex);
        tracks = m_tracks;
    }

    stopPlayback();
    if (tracks.empty()) return false;

    LoadedWav loadedWav;
    if (!readWavFile(tracks.front().wavPath, &loadedWav)) return false;
    if (loadedWav.sampleRate != m_sampleRate) return false;

    auto playbackState = std::make_shared<PlaybackState>();
    playbackState->channelCount = loadedWav.channelCount;
    playbackState->samples = std::move(loadedWav.samples);
    std::atomic_store(&m_playbackState, std::shared_ptr<const PlaybackState>(playbackState));
    m_playbackFramePosition.store(0);
    m_isPlaying = true;
    return true;
}

bool AudioEngine::startPlayback(float gain) {
    setPlaybackGain(gain);
    return loadPlaybackTrack();
}

void AudioEngine::setPlaybackGain(float gain) {
    m_playbackGain.store(gain);
}

bool AudioEngine::isPlaybackActive() const {
    return m_isPlaying.load();
}

void AudioEngine::stopPlayback() {
    m_isPlaying = false;
    m_playbackFramePosition.store(0);
    std::atomic_store(&m_playbackState, std::shared_ptr<const PlaybackState>{});
}

void AudioEngine::render(float *outputInterleaved,
                         int32_t numFrames,
                         int32_t channels,
                         int32_t /*sampleRate*/) {
    if (!outputInterleaved || numFrames <= 0 || channels <= 0) return;

    const size_t sampleCount = static_cast<size_t>(numFrames * channels);
    std::fill(outputInterleaved, outputInterleaved + sampleCount, 0.0f);

    const auto playbackState = std::atomic_load(&m_playbackState);
    if (!playbackState || !m_isPlaying || playbackState->channelCount <= 0 || playbackState->samples.empty()) return;

    const size_t totalFrames = playbackState->samples.size() / static_cast<size_t>(playbackState->channelCount);
    size_t playbackFramePosition = m_playbackFramePosition.load();
    bool reachedPlaybackEnd = false;
    for (int32_t frame = 0; frame < numFrames; ++frame) {
        if (playbackFramePosition >= totalFrames) {
            reachedPlaybackEnd = true;
            break;
        }

        for (int32_t channel = 0; channel < channels; ++channel) {
            outputInterleaved[static_cast<size_t>(frame * channels + channel)] =
                ReadPlaybackSample(playbackState->samples, playbackFramePosition, playbackState->channelCount, channel) *
                m_playbackGain.load();
        }

        ++playbackFramePosition;
    }

    m_playbackFramePosition.store(playbackFramePosition);
    if (!reachedPlaybackEnd) {
        return;
    }

    const auto activePlaybackState = std::atomic_load(&m_playbackState);
    if (activePlaybackState == playbackState) {
        m_isPlaying = false;
        m_playbackFramePosition.store(0);
        std::atomic_store(&m_playbackState, std::shared_ptr<const PlaybackState>{});
    }
}

} // namespace dawengine