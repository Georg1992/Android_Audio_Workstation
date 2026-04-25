#pragma once

#include "AudioSource.h"

#include <cstdint>
#include <cstdio>
#include <memory>
#include <string>

namespace dawengine {

/**
 * [IAudioSource] backed by a local PCM-WAV file.
 *
 * The constructor only stores the path; call [open] before reading. After a
 * successful open the file handle stays alive for the lifetime of the source,
 * so [readFrames] never reopens the file and never decodes anything it does
 * not need.
 *
 * Decoding is restricted to the WAV shape produced and validated upstream:
 * uncompressed PCM (audioFormat == 1), 16-bit samples, 1 or 2 channels. The
 * [WavAudioImporter] on the Kotlin side guarantees imports match these
 * constraints; the recorder writes the same shape. We deliberately do not
 * fall back to a different decoder here — anything else is a hard failure.
 */
class LocalWavSource : public IAudioSource {
public:
    explicit LocalWavSource(std::string path);
    ~LocalWavSource() override;

    LocalWavSource(const LocalWavSource &) = delete;
    LocalWavSource &operator=(const LocalWavSource &) = delete;

    /** Opens the file, validates the header, and positions the cursor at frame 0. */
    bool open();

    int32_t readFrames(float *dst, int32_t frames) override;
    bool seekToFrame(int64_t framePosition) override;
    int64_t totalFrames() const override { return m_totalFrames; }
    int32_t sampleRate() const override { return m_sampleRate; }
    int32_t channelCount() const override { return m_channelCount; }

    const std::string &path() const { return m_path; }

private:
    std::string m_path;
    std::FILE *m_file = nullptr;
    long m_dataChunkOffset = 0;
    int64_t m_totalFrames = 0;
    int32_t m_sampleRate = 0;
    int32_t m_channelCount = 0;
    int64_t m_currentFrame = 0;
};

} // namespace dawengine
