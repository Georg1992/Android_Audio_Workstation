#include "OboeOutput.h"
#include "AudioEngine.h"
#include "OboeTimestampDiagnostics.h"
#include "AudioStreamConfigurationDiagnostics.h"
#include "TransportClockAnchor.h"
#include "TransportClockDiagnostics.h"

#include <algorithm>

OboeOutput::OboeOutput(dawengine::AudioEngine *engine)
    : m_engine(engine) {}

OboeOutput::~OboeOutput() { release(); }

bool OboeOutput::ensureStarted(int32_t sampleRate, int32_t channelCount) {
    if (m_engine) {
        m_engine->logPlaybackStartupMilestone("oboe_ensure_started_begin");
    }
    // Already running with the right format — nothing to do. This is the hot
    // path for back-to-back plays of the same project: the stream stays open,
    // the engine just swaps the source.
    if (m_stream &&
        m_openedSampleRate == sampleRate &&
        m_openedChannelCount == channelCount &&
        m_stream->getState() == oboe::StreamState::Started) {
        captureStreamSnapshot();
        if (m_engine) {
            m_engine->logPlaybackStartupMilestone("oboe_stream_already_started");
        }
        return true;
    }

    // JNI may have paused the stream before [setPlaybackSource] / [stopPlayback].
    // Resume without rebuilding the device.
    if (m_stream &&
        m_openedSampleRate == sampleRate &&
        m_openedChannelCount == channelCount &&
        m_stream->getState() == oboe::StreamState::Paused) {
        const bool resumed = m_stream->requestStart() == oboe::Result::OK;
        if (m_engine && resumed) {
            oboe_timestamp_diag::resetOutputTimestampDiagnostics();
            m_engine->logPlaybackStartupMilestone("oboe_stream_resumed_from_paused");
        }
        return resumed;
    }

    // Format change (rare — only happens if the user switches projects with a
    // different sample rate). Tear down before reopening; we can't reuse a
    // stream across format changes.
    if (m_stream) {
        release();
    }

    if (m_engine) {
        m_engine->logPlaybackStartupMilestone("oboe_stream_open_begin");
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
    const oboe::Result openResult = builder->openStream(stream);
    m_openRequest = {};
    m_openRequest.audioApi = oboe::AudioApi::Unspecified;
    m_openRequest.performanceMode = oboe::PerformanceMode::LowLatency;
    m_openRequest.sharingMode = oboe::SharingMode::Shared;
    m_openRequest.sampleRateHz = sampleRate;
    m_openRequest.channelCount = channelCount;
    audio_stream_config_diag::logOpenedStream(
        "output",
        m_openRequest,
        stream ? stream.get() : nullptr,
        openResult);
    if (openResult != oboe::Result::OK || !stream) return false;
    if (m_engine) {
        m_engine->logPlaybackStartupMilestone("oboe_stream_open_done");
    }

    if (stream->requestStart() != oboe::Result::OK) {
        stream->close();
        return false;
    }
    oboe_timestamp_diag::resetOutputTimestampDiagnostics();
    if (m_engine) {
        m_engine->logPlaybackStartupMilestone("oboe_stream_request_start_done");
    }

    m_stream = stream;
    m_openedSampleRate = sampleRate;
    m_openedChannelCount = channelCount;
    captureStreamSnapshot();
    return true;
}

void OboeOutput::captureStreamSnapshot() {
    dawengine::AudioEngine::OboeStreamSnapshot snapshot;
    if (m_stream) {
        snapshot.sampleRateHz = m_stream->getSampleRate();
        snapshot.channelCount = m_stream->getChannelCount();
        snapshot.framesPerBurst = m_stream->getFramesPerBurst();
        snapshot.bufferCapacityInFrames = m_stream->getBufferCapacityInFrames();
        snapshot.bufferSizeInFrames = m_stream->getBufferSizeInFrames();
        snapshot.performanceMode = static_cast<int32_t>(m_stream->getPerformanceMode());
        snapshot.sharingMode = static_cast<int32_t>(m_stream->getSharingMode());
        snapshot.audioSessionId = static_cast<int32_t>(m_stream->getSessionId());
    }
    m_streamSnapshot = snapshot;
    if (m_engine) {
        m_engine->setOutputStreamForDiagnostics(m_stream);
    }
}

dawengine::AudioEngine::OboeStreamSnapshot OboeOutput::outputStreamSnapshot() const {
    return m_streamSnapshot;
}

void OboeOutput::release() {
    if (m_engine) {
        m_engine->setOutputStreamForDiagnostics(nullptr);
    }
    if (m_stream) {
        m_stream->requestStop();
        constexpr int64_t kStepTimeoutNanos = 100 * oboe::kNanosPerMillisecond;
        for (int guard = 0; guard < 100; ++guard) {
            const oboe::StreamState currentState = m_stream->getState();
            if (currentState != oboe::StreamState::Started &&
                currentState != oboe::StreamState::Starting &&
                currentState != oboe::StreamState::Stopping) {
                break;
            }

            oboe::StreamState nextState = oboe::StreamState::Unknown;
            const oboe::Result result =
                m_stream->waitForStateChange(currentState, &nextState, kStepTimeoutNanos);
            if (result != oboe::Result::OK && result != oboe::Result::ErrorTimeout) {
                break;
            }
        }
        m_stream->close();
        m_stream.reset();
    }
    m_openedSampleRate = 0;
    m_openedChannelCount = 0;
}

bool OboeOutput::pauseForSafeEngineMutation() {
    if (m_engine) {
        m_engine->logPlaybackStartupMilestone("oboe_pause_before_arm_begin");
    }
    if (!m_stream) {
        if (m_engine) {
            m_engine->logPlaybackStartupMilestone("oboe_pause_before_arm_no_stream");
        }
        return true;
    }
    oboe::StreamState currentState = m_stream->getState();
    if (currentState != oboe::StreamState::Started &&
        currentState != oboe::StreamState::Starting &&
        currentState != oboe::StreamState::Pausing) {
        if (m_engine) {
            m_engine->logPlaybackStartupMilestone("oboe_pause_before_arm_done");
        }
        return true;
    }

    if (currentState != oboe::StreamState::Pausing &&
        m_stream->requestPause() != oboe::Result::OK) {
        return false;
    }

    constexpr int64_t kStepTimeoutNanos = 100 * oboe::kNanosPerMillisecond;
    for (int guard = 0; guard < 100; ++guard) {
        currentState = m_stream->getState();
        if (currentState == oboe::StreamState::Paused) {
            if (m_engine) {
                m_engine->logPlaybackStartupMilestone("oboe_pause_before_arm_done");
            }
            return true;
        }
        if (currentState != oboe::StreamState::Started &&
            currentState != oboe::StreamState::Starting &&
            currentState != oboe::StreamState::Pausing) {
            if (m_engine) {
                m_engine->logPlaybackStartupMilestone("oboe_pause_before_arm_done");
            }
            return true;
        }
        oboe::StreamState nextState = oboe::StreamState::Unknown;
        const oboe::Result result =
            m_stream->waitForStateChange(currentState, &nextState, kStepTimeoutNanos);
        if (result != oboe::Result::OK && result != oboe::Result::ErrorTimeout) {
            return false;
        }
    }
    return false;
}

oboe::DataCallbackResult OboeOutput::onAudioReady(oboe::AudioStream *stream,
                                                  void *audioData,
                                                  int32_t numFrames) {
    auto *out = static_cast<float *>(audioData);
    if (!out || numFrames <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    const int32_t channels =
        stream && stream->getChannelCount() > 0 ? stream->getChannelCount() : m_openedChannelCount;
    if (channels <= 0) {
        return oboe::DataCallbackResult::Continue;
    }

    const std::size_t sampleCount =
        static_cast<std::size_t>(numFrames) * static_cast<std::size_t>(channels);
    std::fill(out, out + sampleCount, 0.0f);

    if (!stream || !m_engine) {
        return oboe::DataCallbackResult::Continue;
    }

    const int64_t callbackStartNs = transport_clock::monotonicNowNs();

    m_engine->logFirstOboeCallbackOnce();

    m_lastCallbackFrames.store(numFrames, std::memory_order_release);

    const int64_t callbackMonotonicNs = transport_clock::monotonicNowNs();
    m_engine->refreshLiveOutputLatencyFromStream(stream);
    const int64_t renderedTransportFrame = m_engine->transportFrame();
    transport_clock_diag::maybeLogOutputClockCorrelation(
        m_engine,
        stream,
        callbackMonotonicNs,
        numFrames);

    oboe_timestamp_diag::maybeLogOutputTimestamp(
        stream,
        renderedTransportFrame,
        renderedTransportFrame,
        numFrames);
    audio_stream_config_diag::maybeLogOutputHardwareFloorOnce(
        "production_playback",
        m_openRequest,
        stream);

    // Engine emits silence when no source is armed, so it's safe to leave the
    // stream running between plays.
    const int64_t renderStartNs = transport_clock::monotonicNowNs();
    m_engine->render(out, numFrames, channels, stream->getSampleRate());
    const int64_t renderEndNs = transport_clock::monotonicNowNs();
    const int64_t callbackEndNs = renderEndNs;
    m_engine->recordOutputCallbackCost(
        numFrames,
        (callbackEndNs - callbackStartNs) / 1000LL,
        (renderEndNs - renderStartNs) / 1000LL);
    return oboe::DataCallbackResult::Continue;
}
