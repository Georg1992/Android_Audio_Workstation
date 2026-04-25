#pragma once

#include <cstdint>

namespace dawengine {

/**
 * Pull-style audio source feeding the streaming playback path.
 *
 * The streaming engine keeps one instance per playable track. The I/O worker
 * thread calls [readFrames] in batches and pushes the result into a ring
 * buffer; the audio render callback never touches an `IAudioSource` directly.
 *
 * Today the only implementation is [LocalWavSource]. Future remote / cached
 * sources (HTTP range, partial download, transcoding decoder) plug in here
 * without touching the engine or the JNI bridge.
 *
 * Implementations must be safe to call from a single producer thread; the
 * engine never reads concurrently from one source.
 */
class IAudioSource {
public:
    virtual ~IAudioSource() = default;

    /**
     * Reads up to `frames` interleaved frames into `dst`. Returns the number of
     * frames actually decoded — 0 on EOF, negative on a hard read failure.
     *
     * `dst` must have room for `frames * channelCount()` floats.
     */
    virtual int32_t readFrames(float *dst, int32_t frames) = 0;

    /**
     * Repositions the read cursor to `framePosition` (0 = start of the audio
     * payload). Returns true on success. Sources backed by non-seekable
     * transports (e.g. live streams) may always return false.
     */
    virtual bool seekToFrame(int64_t framePosition) = 0;

    /** Total number of frames in the source, or -1 if not knowable up front. */
    virtual int64_t totalFrames() const = 0;

    virtual int32_t sampleRate() const = 0;
    virtual int32_t channelCount() const = 0;
};

} // namespace dawengine
