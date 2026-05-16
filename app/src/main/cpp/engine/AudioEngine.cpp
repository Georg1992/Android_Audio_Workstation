#include "AudioEngine.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <limits>
#include <utility>

#include "AudioSource.h"
#include "LocalWavSource.h"

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

int16_t FloatToPcm16(float sample) {
    const float clamped = std::max(-1.0f, std::min(1.0f, sample));
    return static_cast<int16_t>(clamped * 32767.0f);
}

} // namespace

namespace dawengine {

namespace playback {
constexpr int32_t kRingDurationSeconds = 1;
// Wall-time preroll: ms × SR / 1000 (computePrerollFramesForSampleRate).
constexpr int32_t kPrerollWallMs = 30;

// Larger I/O batches — producer chunk size in frames only (not tied to SR).
constexpr int32_t kIoBatchFrames = 1'024;
constexpr int kIoIdleSleepMs = 4;

constexpr int32_t kMaxRenderFramesPerCallback = 4'096;
constexpr std::size_t kRenderScratchFloatCount =
    static_cast<std::size_t>(kMaxRenderFramesPerCallback) * 2u;

// Transparent master safety stage: exact pass-through below the threshold,
// then a smooth asymptotic knee that prevents summed playback from hard clipping.
constexpr float kMasterSafetyThreshold = 0.99f;
constexpr float kMasterSafetyHeadroom = 1.0f - kMasterSafetyThreshold;

float ApplyMasterSafetySoftClip(float sample) {
    const float magnitude = sample < 0.0f ? -sample : sample;
    if (magnitude <= kMasterSafetyThreshold) {
        return sample;
    }

    const float over = magnitude - kMasterSafetyThreshold;
    const float shaped = kMasterSafetyThreshold +
                         kMasterSafetyHeadroom * (over / (over + kMasterSafetyHeadroom));
    return sample < 0.0f ? -shaped : shaped;
}

void ProcessMasterSafetySoftClip(float *interleaved, std::size_t sampleCount) {
    for (std::size_t i = 0; i < sampleCount; ++i) {
        interleaved[i] = ApplyMasterSafetySoftClip(interleaved[i]);
    }
}

} // namespace playback

struct PendingPlaybackLane {
    std::shared_ptr<IAudioSource> source;
    std::unique_ptr<RingBuffer> ring;
    std::string path;
    float gain = 1.0f;
    int32_t channels = 0;
};

AudioEngine::AudioEngine() = default;

int32_t AudioEngine::computeRingFramesForSampleRate(int32_t sampleRateHz) {
    return static_cast<int32_t>(static_cast<int64_t>(sampleRateHz) *
                                static_cast<int64_t>(playback::kRingDurationSeconds));
}

int32_t AudioEngine::computePrerollFramesForSampleRate(int32_t sampleRateHz) {
    return static_cast<int32_t>((static_cast<int64_t>(sampleRateHz) *
                                 static_cast<int64_t>(playback::kPrerollWallMs)) /
                                1000);
}

void AudioEngine::clearPlaybackLanesLocked() {
    for (PlaybackLaneSlot &lane : m_playbackLanes) {
        lane.ring.reset();
        lane.source.reset();
        lane.currentPath.clear();
        lane.sourceExhausted.store(false, std::memory_order_release);
        lane.gain.store(1.0f, std::memory_order_release);
        lane.srcChannels.store(0, std::memory_order_release);
    }
}

void AudioEngine::deactivateAuxiliaryLanesLocked() {
    for (std::size_t i = 1; i < kPlaybackLaneCount; ++i) {
        PlaybackLaneSlot &lane = m_playbackLanes[i];
        lane.ring.reset();
        lane.source.reset();
        lane.currentPath.clear();
        lane.sourceExhausted.store(false, std::memory_order_release);
        lane.gain.store(1.0f, std::memory_order_release);
        lane.srcChannels.store(0, std::memory_order_release);
    }
}

bool AudioEngine::armSinglePlaybackLaneLocked(const std::string &wavPath, float laneGain) {
    deactivateAuxiliaryLanesLocked();

    PlaybackLaneSlot &lane0 = m_playbackLanes[0];
    lane0.gain.store(laneGain, std::memory_order_release);

    const bool samePath =
        !!lane0.source && wavPath == lane0.currentPath;
    const int32_t ringFrameCapacity = computeRingFramesForSampleRate(m_sampleRate);
    const int32_t prerollTargetFrames = computePrerollFramesForSampleRate(m_sampleRate);

    if (samePath) {
        if (!lane0.source->seekToFrame(0)) return false;
        if (lane0.ring) lane0.ring->reset();
    } else {
        auto source = std::make_shared<LocalWavSource>(wavPath);
        if (!source->open()) return false;
        if (source->sampleRate() != m_sampleRate) return false;
        if (source->channelCount() < 1 || source->channelCount() > 2) return false;

        const std::size_t ringFloats = static_cast<std::size_t>(ringFrameCapacity) *
                                       static_cast<std::size_t>(source->channelCount());
        lane0.ring = std::make_unique<RingBuffer>(ringFloats);
        lane0.srcChannels.store(source->channelCount(), std::memory_order_release);
        lane0.source = std::move(source);
        lane0.currentPath = wavPath;
    }

    lane0.sourceExhausted.store(false, std::memory_order_release);

    // Grow once on the JNI thread while Oboe is paused — [render] never resizes.
    m_renderScratch.resize(playback::kRenderScratchFloatCount);

    const int32_t channels = lane0.source->channelCount();
    std::vector<float> preroll(static_cast<std::size_t>(prerollTargetFrames) *
                               static_cast<std::size_t>(channels));
    const int32_t prerollFrames = lane0.source->readFrames(preroll.data(), prerollTargetFrames);
    if (prerollFrames > 0 && lane0.ring) {
        lane0.ring->write(
            preroll.data(),
            static_cast<std::size_t>(prerollFrames) * static_cast<std::size_t>(channels));
    }

    return true;
}

bool AudioEngine::armPlaybackLanesLocked(const std::vector<std::string> &wavPaths,
                                         const std::vector<float> &gains) {
    const std::size_t laneCount = wavPaths.size();
    if (laneCount == 0 ||
        laneCount > kPlaybackLaneProductCap ||
        laneCount != gains.size()) {
        clearPlaybackLanesLocked();
        return false;
    }

    if (laneCount == 1) {
        if (!armSinglePlaybackLaneLocked(wavPaths[0], gains[0])) {
            clearPlaybackLanesLocked();
            return false;
        }
        return true;
    }

    std::array<PendingPlaybackLane, kPlaybackLaneProductCap> pending{};
    const int32_t ringFrameCapacity = computeRingFramesForSampleRate(m_sampleRate);
    const int32_t prerollTargetFrames = computePrerollFramesForSampleRate(m_sampleRate);

    for (std::size_t laneIdx = 0; laneIdx < laneCount; ++laneIdx) {
        const std::string &path = wavPaths[laneIdx];
        if (path.empty()) {
            clearPlaybackLanesLocked();
            return false;
        }

        auto source = std::make_shared<LocalWavSource>(path);
        if (!source->open()) {
            clearPlaybackLanesLocked();
            return false;
        }
        if (source->sampleRate() != m_sampleRate) {
            clearPlaybackLanesLocked();
            return false;
        }
        if (source->channelCount() < 1 || source->channelCount() > 2) {
            clearPlaybackLanesLocked();
            return false;
        }

        const std::size_t ringFloats = static_cast<std::size_t>(ringFrameCapacity) *
                                       static_cast<std::size_t>(source->channelCount());
        auto ring = std::make_unique<RingBuffer>(ringFloats);

        std::vector<float> preroll(static_cast<std::size_t>(prerollTargetFrames) *
                                   static_cast<std::size_t>(source->channelCount()));
        const int32_t prerollFrames = source->readFrames(preroll.data(), prerollTargetFrames);
        if (prerollFrames < 0) {
            clearPlaybackLanesLocked();
            return false;
        }
        if (prerollFrames > 0) {
            ring->write(
                preroll.data(),
                static_cast<std::size_t>(prerollFrames) *
                    static_cast<std::size_t>(source->channelCount()));
        }

        PendingPlaybackLane &pendingLane = pending[laneIdx];
        pendingLane.channels = source->channelCount();
        pendingLane.gain = gains[laneIdx];
        pendingLane.path = path;
        pendingLane.ring = std::move(ring);
        pendingLane.source = std::move(source);
    }

    clearPlaybackLanesLocked();
    m_renderScratch.resize(playback::kRenderScratchFloatCount);

    for (std::size_t laneIdx = 0; laneIdx < laneCount; ++laneIdx) {
        PlaybackLaneSlot &lane = m_playbackLanes[laneIdx];
        PendingPlaybackLane &pendingLane = pending[laneIdx];

        lane.ring = std::move(pendingLane.ring);
        lane.source = std::move(pendingLane.source);
        lane.currentPath = std::move(pendingLane.path);
        lane.sourceExhausted.store(false, std::memory_order_release);
        lane.gain.store(pendingLane.gain, std::memory_order_release);
        lane.srcChannels.store(pendingLane.channels, std::memory_order_release);
    }

    return true;
}

AudioEngine::~AudioEngine() {
    releasePlaybackResources();
    stopRecording();
}

void AudioEngine::configureProject(int32_t sampleRate, int32_t fileBitDepth) {
    m_sampleRate = sampleRate;
    m_fileBitDepth = fileBitDepth;
}

// ---------------------------------------------------------------------------
// Recording
// ---------------------------------------------------------------------------

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

// ---------------------------------------------------------------------------
// Playback (streaming)
// ---------------------------------------------------------------------------

bool AudioEngine::setPlaybackSource(const std::string &wavPath, float gain) {
    return setPlaybackSources(std::vector<std::string>{wavPath}, std::vector<float>{gain});
}

bool AudioEngine::setPlaybackSources(const std::vector<std::string> &wavPaths,
                                     const std::vector<float> &gains) {
    // JNI pauses Oboe before this call; stop the I/O producer as well before
    // resetting/replacing lane rings or sources.
    m_isPlaying.store(false, std::memory_order_release);
    stopIoThread();

    {
        std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
        if (!armPlaybackLanesLocked(wavPaths, gains)) {
            return false;
        }
    }

    ensureIoThreadRunning();
    m_isPlaying.store(true, std::memory_order_release);
    return true;
}

void AudioEngine::setPlaybackGain(float gain) {
    m_playbackLanes[0].gain.store(gain, std::memory_order_release);
}

void AudioEngine::stopPlayback() {
    // JNI pauses Oboe before stop; joining here quiesces the ring producer so
    // RingBuffer::reset observes its documented producer+consumer stop rule.
    m_isPlaying.store(false, std::memory_order_release);
    stopIoThread();

    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);

    for (PlaybackLaneSlot &lane : m_playbackLanes) {
        lane.sourceExhausted.store(false, std::memory_order_release);
        if (lane.ring) {
            lane.ring->reset();
        }
        if (lane.source) {
            lane.source->seekToFrame(0);
        }
    }
}

void AudioEngine::releasePlaybackResources() {
    m_isPlaying.store(false, std::memory_order_release);
    stopIoThread();

    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
    clearPlaybackLanesLocked();
}

void AudioEngine::ensureIoThreadRunning() {
    if (m_ioRunning.load(std::memory_order_acquire)) return;
    m_ioRunning.store(true, std::memory_order_release);
    m_ioThread = std::thread(&AudioEngine::ioLoop, this);
}

void AudioEngine::stopIoThread() {
    if (!m_ioRunning.exchange(false, std::memory_order_acq_rel)) {
        return;
    }
    if (m_ioThread.joinable()) {
        m_ioThread.join();
    }
}

void AudioEngine::ioLoop() {
    std::vector<float> scratch;
    while (m_ioRunning.load(std::memory_order_acquire)) {
        if (!m_isPlaying.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(playback::kIoIdleSleepMs));
            continue;
        }

        bool progressed = false;
        for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
            std::shared_ptr<IAudioSource> source;
            RingBuffer *ring = nullptr;
            int32_t channels = 0;
            bool laneExhausted = false;

            {
                std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
                PlaybackLaneSlot &lane = m_playbackLanes[laneIdx];
                source = lane.source;
                ring = lane.ring.get();
                channels = lane.srcChannels.load(std::memory_order_acquire);
                laneExhausted = lane.sourceExhausted.load(std::memory_order_acquire);
            }

            if (!source || !ring || channels <= 0 || laneExhausted) {
                continue;
            }

            const std::size_t writableFloats = ring->writable();
            const std::size_t writableFrames = writableFloats / static_cast<std::size_t>(channels);
            if (writableFrames < static_cast<std::size_t>(playback::kIoBatchFrames)) {
                continue;
            }

            const std::size_t batchFloats =
                static_cast<std::size_t>(playback::kIoBatchFrames) * static_cast<std::size_t>(channels);
            if (scratch.size() < batchFloats) {
                scratch.resize(batchFloats);
            }

            const int32_t framesRead =
                source->readFrames(scratch.data(), playback::kIoBatchFrames);

            if (framesRead > 0) {
                ring->write(
                    scratch.data(),
                    static_cast<std::size_t>(framesRead) * static_cast<std::size_t>(channels));
                progressed = true;
            } else if (framesRead == 0) {
                m_playbackLanes[laneIdx].sourceExhausted.store(true, std::memory_order_release);
                progressed = true;
            } else {
                m_playbackLanes[laneIdx].sourceExhausted.store(true, std::memory_order_release);
                progressed = true;
            }
        }

        if (!progressed) {
            std::this_thread::sleep_for(std::chrono::milliseconds(playback::kIoIdleSleepMs));
        }
    }
}

void AudioEngine::renderMaybeCompletePlaybackMaster(int32_t numFramesOutput,
                                                   int32_t /*outChannels*/,
                                                   int32_t minimumFramesReturnedFromLanes) {
    if (minimumFramesReturnedFromLanes >= numFramesOutput) {
        return;
    }

    bool allDrained = true;
    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
        const int32_t srcChannels =
            m_playbackLanes[laneIdx].srcChannels.load(std::memory_order_acquire);
        if (srcChannels <= 0) {
            continue;
        }

        RingBuffer *ring = m_playbackLanes[laneIdx].ring.get();
        if (!ring) {
            allDrained = false;
            break;
        }

        const bool exhausted =
            m_playbackLanes[laneIdx].sourceExhausted.load(std::memory_order_acquire);

        if (!exhausted || ring->readable() != 0) {
            allDrained = false;
            break;
        }
    }

    if (allDrained) {
        m_isPlaying.store(false, std::memory_order_release);
    }
}

void AudioEngine::render(float *outputInterleaved,
                         int32_t numFrames,
                         int32_t channels,
                         int32_t /*sampleRate*/) {
    if (!outputInterleaved || numFrames <= 0 || channels <= 0) return;

    const std::size_t outSampleCount = static_cast<std::size_t>(numFrames) *
                                       static_cast<std::size_t>(channels);
    std::fill(outputInterleaved, outputInterleaved + outSampleCount, 0.0f);

    if (!m_isPlaying.load(std::memory_order_acquire)) return;

    int32_t minFramesReturned = std::numeric_limits<int32_t>::max();
    bool readAnyLane = false;

    for (std::size_t laneIdx = 0; laneIdx < kPlaybackLaneCount; ++laneIdx) {
        const int32_t srcChannels =
            m_playbackLanes[laneIdx].srcChannels.load(std::memory_order_acquire);
        if (srcChannels <= 0) {
            continue;
        }

        RingBuffer *ring = m_playbackLanes[laneIdx].ring.get();
        if (!ring) {
            continue;
        }

        const std::size_t neededFloats = static_cast<std::size_t>(numFrames) *
                                         static_cast<std::size_t>(srcChannels);
        const std::size_t scratchFloats = std::min(neededFloats, m_renderScratch.size());

        const std::size_t floatsRead =
            ring->read(m_renderScratch.data(), scratchFloats);
        const int32_t framesReturned =
            static_cast<int32_t>(floatsRead / static_cast<std::size_t>(srcChannels));

        readAnyLane = true;
        minFramesReturned = std::min(minFramesReturned, framesReturned);

        const float gain =
            m_playbackLanes[laneIdx].gain.load(std::memory_order_acquire);
        for (int32_t frame = 0; frame < framesReturned; ++frame) {
            const std::size_t srcBase =
                static_cast<std::size_t>(frame) * static_cast<std::size_t>(srcChannels);
            for (int32_t outCh = 0; outCh < channels; ++outCh) {
                const int32_t sourceChannel = std::min(outCh, srcChannels - 1);
                outputInterleaved[static_cast<std::size_t>(frame * channels + outCh)] +=
                    m_renderScratch[srcBase + static_cast<std::size_t>(sourceChannel)] * gain;
            }
        }
    }

    if (readAnyLane) {
        playback::ProcessMasterSafetySoftClip(outputInterleaved, outSampleCount);

        const int32_t reportedMin =
            (minFramesReturned == std::numeric_limits<int32_t>::max()) ? numFrames : minFramesReturned;
        renderMaybeCompletePlaybackMaster(numFrames, channels, reportedMin);
    }
}


} // namespace dawengine
