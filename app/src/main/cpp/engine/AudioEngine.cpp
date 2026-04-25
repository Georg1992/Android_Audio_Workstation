#include "AudioEngine.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdio>
#include <cstring>

#include "AudioSource.h"
#include "LocalWavSource.h"

namespace {

constexpr int64_t kReadTimeoutNanos = 100 * oboe::kNanosPerMillisecond;
constexpr int32_t kFramesPerRead = 256;
constexpr uint16_t kWavBitsPerSample = 16;

// Tunables for the streaming playback path.
//
// The ring is sized for ~1 second of stereo float audio at the highest sample
// rate we support. That gives the I/O thread plenty of headroom even under
// scheduler pressure while staying well under 1 MiB.
constexpr std::size_t kPlaybackRingFrames = 48'000;

// Larger I/O batches mean fewer system calls per second; smaller batches mean
// the ring stays nicely filled at startup. 4 KiB worth of frames hits a good
// middle ground.
constexpr int32_t kIoBatchFrames = 1'024;

// I/O thread sleep when there is no work (paused / ring full / waiting for
// next batch). Short enough to keep the prefetch responsive, long enough to
// cost effectively zero CPU when idle.
constexpr int kIoIdleSleepMs = 4;

// Pre-roll target before starting playback, so the audio thread never reads
// from an empty ring on the first callback. ~30 ms at 48 kHz is comfortable
// against typical Oboe burst sizes (96..480 frames).
constexpr int32_t kPrerollFrames = 1'440;

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

AudioEngine::AudioEngine() = default;

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
    if (wavPath.empty()) return false;

    setPlaybackGain(gain);

    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);

    // Pause the audio output side first so the render thread observes a
    // consistent snapshot of source + ring while we mutate them. The I/O
    // thread checks `m_isPlaying` before pulling from the source, so dropping
    // it here also stops the producer.
    m_isPlaying.store(false, std::memory_order_release);

    const bool samePath = (m_source && wavPath == m_currentSourcePath);
    if (samePath) {
        // Cheap rewind: keep the open file handle, seek to frame 0, drop any
        // stale data from the ring so the next render starts at the head.
        if (!m_source->seekToFrame(0)) return false;
        if (m_ring) m_ring->reset();
    } else {
        // Open a fresh source. We allocate the ring lazily — its size depends
        // on the source's channel count, which we only know after a successful
        // open.
        auto source = std::make_shared<LocalWavSource>(wavPath);
        if (!source->open()) return false;
        if (source->sampleRate() != m_sampleRate) return false;
        if (source->channelCount() < 1 || source->channelCount() > 2) return false;

        const std::size_t ringFloats = static_cast<std::size_t>(kPlaybackRingFrames) *
                                       static_cast<std::size_t>(source->channelCount());
        m_ring = std::make_unique<RingBuffer>(ringFloats);
        m_sourceChannelCount.store(source->channelCount(), std::memory_order_release);
        m_source = std::move(source);
        m_currentSourcePath = wavPath;
    }

    m_sourceExhausted.store(false, std::memory_order_release);

    // Pre-roll: pull some PCM synchronously so the very first audio callback
    // already has data. Without this the first ~10 ms of every play would be
    // silence while the I/O thread spins up.
    const int32_t channels = m_source->channelCount();
    std::vector<float> preroll(static_cast<std::size_t>(kPrerollFrames) * channels);
    const int32_t prerollFrames = m_source->readFrames(preroll.data(), kPrerollFrames);
    if (prerollFrames > 0) {
        m_ring->write(preroll.data(),
                      static_cast<std::size_t>(prerollFrames) * static_cast<std::size_t>(channels));
    }

    ensureIoThreadRunning();

    m_isPlaying.store(true, std::memory_order_release);
    return true;
}

void AudioEngine::setPlaybackGain(float gain) {
    m_playbackGain.store(gain, std::memory_order_release);
}

void AudioEngine::stopPlayback() {
    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);

    m_isPlaying.store(false, std::memory_order_release);
    m_sourceExhausted.store(false, std::memory_order_release);
    if (m_ring) m_ring->reset();

    // Rewind the open source so a subsequent play (with the same path) is
    // instant — no header parse, no fresh fopen, no allocation.
    if (m_source) {
        m_source->seekToFrame(0);
    }
}

void AudioEngine::releasePlaybackResources() {
    stopIoThread();

    std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
    m_source.reset();
    m_currentSourcePath.clear();
    m_ring.reset();
    m_sourceChannelCount.store(0, std::memory_order_release);
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
        if (!m_isPlaying.load(std::memory_order_acquire) ||
            m_sourceExhausted.load(std::memory_order_acquire)) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kIoIdleSleepMs));
            continue;
        }

        // Pull a snapshot of the source and ring while holding the lock briefly,
        // then release it for the actual read so JNI calls aren't blocked by
        // file I/O. The shared_ptr / raw pointer both stay valid for the
        // duration of this iteration because [releasePlaybackResources] joins
        // this thread before resetting them.
        std::shared_ptr<IAudioSource> source;
        RingBuffer *ring = nullptr;
        int32_t channels = 0;
        {
            std::lock_guard<std::mutex> playbackLock(m_playbackMutex);
            source = m_source;
            ring = m_ring.get();
            channels = m_sourceChannelCount.load(std::memory_order_acquire);
        }
        if (!source || !ring || channels <= 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(kIoIdleSleepMs));
            continue;
        }

        const std::size_t writableFloats = ring->writable();
        const std::size_t writableFrames = writableFloats / static_cast<std::size_t>(channels);
        if (writableFrames < static_cast<std::size_t>(kIoBatchFrames)) {
            // Ring is comfortably ahead of the consumer; back off briefly.
            std::this_thread::sleep_for(std::chrono::milliseconds(kIoIdleSleepMs));
            continue;
        }

        const std::size_t batchFloats = static_cast<std::size_t>(kIoBatchFrames) *
                                        static_cast<std::size_t>(channels);
        if (scratch.size() < batchFloats) {
            scratch.resize(batchFloats);
        }
        const int32_t framesRead = source->readFrames(scratch.data(), kIoBatchFrames);
        if (framesRead > 0) {
            ring->write(scratch.data(),
                        static_cast<std::size_t>(framesRead) * static_cast<std::size_t>(channels));
        }
        if (framesRead == 0) {
            // EOF — let the render callback drain the ring and flip
            // [m_isPlaying] back to false from the audio thread.
            m_sourceExhausted.store(true, std::memory_order_release);
        } else if (framesRead < 0) {
            // Hard read error: mark exhausted so the render thread stops
            // emitting once the ring drains. We deliberately don't paper over
            // this — the user-visible stop signal makes the failure obvious
            // instead of looping forever on a broken file.
            m_sourceExhausted.store(true, std::memory_order_release);
        }
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

    // Lock-free reads — the producer (I/O thread) and consumer (this thread)
    // share the ring through the SPSC contract and the [m_sourceChannelCount]
    // atomic. The shared_ptr/unique_ptr fields are not touched here.
    const int32_t srcChannels = m_sourceChannelCount.load(std::memory_order_acquire);
    if (srcChannels <= 0) return;

    // Pre-allocated render scratch sized for the largest reasonable callback.
    // Oboe LowLatency callbacks max out around 1024 frames; using a static
    // thread-local keeps us out of the heap on the audio thread.
    static thread_local std::vector<float> renderScratch;
    const std::size_t neededFloats = static_cast<std::size_t>(numFrames) *
                                     static_cast<std::size_t>(srcChannels);
    if (renderScratch.size() < neededFloats) {
        renderScratch.resize(neededFloats);
    }

    // Snapshot the ring pointer locally. Both this and `m_ring` are only ever
    // mutated under `m_playbackMutex` from the JNI thread, and the I/O thread
    // is joined before that mutation, so the pointer is stable for the entire
    // lifetime of one playback session.
    RingBuffer *ring = m_ring.get();
    if (!ring) return;

    const std::size_t floatsRead = ring->read(renderScratch.data(), neededFloats);
    const int32_t framesRead = static_cast<int32_t>(floatsRead / static_cast<std::size_t>(srcChannels));

    const float gain = m_playbackGain.load(std::memory_order_acquire);
    for (int32_t frame = 0; frame < framesRead; ++frame) {
        const std::size_t srcBase = static_cast<std::size_t>(frame) *
                                    static_cast<std::size_t>(srcChannels);
        for (int32_t channel = 0; channel < channels; ++channel) {
            // Mono → fan out, stereo → straight pass, > stereo never happens
            // for our sources (validated at open time).
            const int32_t sourceChannel = std::min(channel, srcChannels - 1);
            outputInterleaved[static_cast<std::size_t>(frame * channels + channel)] =
                renderScratch[srcBase + static_cast<std::size_t>(sourceChannel)] * gain;
        }
    }

    if (framesRead < numFrames &&
        m_sourceExhausted.load(std::memory_order_acquire) &&
        ring->readable() == 0) {
        // Source ran out and the ring is empty — flip the public play flag.
        // The Kotlin side observes this via [isPlaybackActive] / the StateFlow
        // wrapper to decide whether to loop or stop the UI clock.
        m_isPlaying.store(false, std::memory_order_release);
    }
}

} // namespace dawengine
