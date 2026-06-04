package com.georgv.audioworkstation.core.audio

import android.media.AudioFormat
import android.media.MediaExtractor
import android.media.MediaFormat

/** Parsed audio track format shared by metadata read and MediaCodec decode. */
internal data class CompressedTrackInfo(
    val mimeType: String,
    val sampleRate: Int,
    val channelCount: Int,
)

internal object CompressedMediaTrack {
    const val DEFAULT_MAX_SAMPLE_READ_BYTES = 256 * 1024

    fun selectAudioTrackIndex(extractor: MediaExtractor): Int? {
        var trackIndex = 0
        while (trackIndex < extractor.trackCount) {
            val mimeType = extractor.getTrackFormat(trackIndex).getString(MediaFormat.KEY_MIME)
            if (mimeType?.startsWith("audio/") == true) {
                return trackIndex
            }
            trackIndex++
        }
        return null
    }

    fun readTrackInfo(trackFormat: MediaFormat): CompressedTrackInfo? {
        val mimeType = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null
        if (!mimeType.startsWith("audio/")) return null
        return CompressedTrackInfo(
            mimeType = mimeType,
            sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
            channelCount = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
        )
    }

    fun readDurationMs(trackFormat: MediaFormat): Long {
        if (!trackFormat.containsKey(MediaFormat.KEY_DURATION)) return 0L
        val durationUs = trackFormat.getLong(MediaFormat.KEY_DURATION)
        if (durationUs <= 0L) return 0L
        return (durationUs + 999L) / 1_000L
    }

    fun readPcmEncoding(format: MediaFormat): Int =
        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
            format.getInteger(MediaFormat.KEY_PCM_ENCODING)
        } else {
            AudioFormat.ENCODING_PCM_16BIT
        }

    fun resolveMaxSampleReadBytes(trackFormat: MediaFormat): Int {
        val formatMaxInputSize =
            if (trackFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                trackFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
            } else {
                0
            }
        return maxOf(formatMaxInputSize, DEFAULT_MAX_SAMPLE_READ_BYTES)
    }
}
