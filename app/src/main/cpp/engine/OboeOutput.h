#pragma once

#include <oboe/Oboe.h>
#include <cstdint>
#include <memory>

namespace dawengine {
class AudioEngine;
}

/**
 * Persistent Oboe output stream.
 *
 * The first call to [ensureStarted] opens the device with LowLatency / Shared
 * settings; subsequent calls are cheap no-ops as long as the requested format
 * matches. The render callback is wired straight to [dawengine::AudioEngine],
 * which now emits silence whenever no track is armed — meaning we can keep
 * the device open between plays and avoid the ~50–150 ms re-open cost on
 * every transport press.
 *
 * The stream is owned for the lifetime of the JNI process and torn down via
 * [release] when the project screen is disposed (so we don't keep the audio
 * device awake in the background).
 */
class OboeOutput : public oboe::AudioStreamCallback {
public:
    explicit OboeOutput(dawengine::AudioEngine *engine);
    ~OboeOutput() override;

    /**
     * Opens and starts the stream if it isn't already, or restarts it if the
     * existing stream uses different parameters. Returns true once the stream
     * is in the started state.
     */
    bool ensureStarted(int32_t sampleRate = 44'100, int32_t channelCount = 2);

    /** Closes the stream. Safe to call repeatedly. */
    void release();

    oboe::DataCallbackResult onAudioReady(oboe::AudioStream *stream,
                                          void *audioData,
                                          int32_t numFrames) override;

private:
    dawengine::AudioEngine *m_engine;
    std::shared_ptr<oboe::AudioStream> m_stream;
    int32_t m_openedSampleRate = 0;
    int32_t m_openedChannelCount = 0;
};
