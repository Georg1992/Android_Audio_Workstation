#include "OboeOutput.h"
#include "AudioEngine.h"

OboeOutput::OboeOutput(dawengine::AudioEngine *engine)
    : m_engine(engine) {}

OboeOutput::~OboeOutput() { release(); }

bool OboeOutput::ensureStarted(int32_t sampleRate, int32_t channelCount) {
    // Already running with the right format — nothing to do. This is the hot
    // path for back-to-back plays of the same project: the stream stays open,
    // the engine just swaps the source.
    if (m_stream &&
        m_openedSampleRate == sampleRate &&
        m_openedChannelCount == channelCount &&
        m_stream->getState() == oboe::StreamState::Started) {
        return true;
    }

    // JNI may have paused the stream before [setPlaybackSource] / [stopPlayback].
    // Resume without rebuilding the device.
    if (m_stream &&
        m_openedSampleRate == sampleRate &&
        m_openedChannelCount == channelCount &&
        m_stream->getState() == oboe::StreamState::Paused) {
        return m_stream->requestStart() == oboe::Result::OK;
    }

    // Format change (rare — only happens if the user switches projects with a
    // different sample rate). Tear down before reopening; we can't reuse a
    // stream across format changes.
    if (m_stream) {
        release();
    }

    auto builder = std::make_unique<oboe::AudioStreamBuilder>();
    builder->setFormat(oboe::AudioFormat::Float);
    builder->setSampleRate(sampleRate);
    builder->setChannelCount(channelCount);
    builder->setSharingMode(oboe::SharingMode::Shared);
    builder->setPerformanceMode(oboe::PerformanceMode::LowLatency);
    builder->setDirection(oboe::Direction::Output);
    builder->setCallback(this);

    std::shared_ptr<oboe::AudioStream> stream;
    if (builder->openStream(stream) != oboe::Result::OK || !stream) return false;

    if (stream->requestStart() != oboe::Result::OK) {
        stream->close();
        return false;
    }

    m_stream = stream;
    m_openedSampleRate = sampleRate;
    m_openedChannelCount = channelCount;
    return true;
}

void OboeOutput::release() {
    if (m_stream) {
        m_stream->requestStop();
        m_stream->close();
        m_stream.reset();
    }
    m_openedSampleRate = 0;
    m_openedChannelCount = 0;
}

void OboeOutput::pauseForSafeEngineMutation() {
    if (!m_stream) return;
    oboe::StreamState currentState = m_stream->getState();
    if (currentState != oboe::StreamState::Started &&
        currentState != oboe::StreamState::Starting) {
        return;
    }

    if (m_stream->requestPause() != oboe::Result::OK) return;

    constexpr int64_t kStepTimeoutNanos = 100 * oboe::kNanosPerMillisecond;
    for (int guard = 0; guard < 100; ++guard) {
        currentState = m_stream->getState();
        if (currentState == oboe::StreamState::Paused) return;
        if (currentState != oboe::StreamState::Started &&
            currentState != oboe::StreamState::Starting &&
            currentState != oboe::StreamState::Pausing) {
            return;
        }
        oboe::StreamState nextState = oboe::StreamState::Unknown;
        const oboe::Result result =
            m_stream->waitForStateChange(currentState, &nextState, kStepTimeoutNanos);
        if (result != oboe::Result::OK && result != oboe::Result::ErrorTimeout) {
            return;
        }
    }
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream *stream,
                                                  void *audioData,
                                                  int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    if (!m_engine || !out) {
        return oboe::DataCallbackResult::Stop;
    }

    // Engine emits silence when no source is armed, so it's safe to leave the
    // stream running between plays.
    m_engine->render(out, numFrames, stream->getChannelCount(), stream->getSampleRate());
    return oboe::DataCallbackResult::Continue;
}
