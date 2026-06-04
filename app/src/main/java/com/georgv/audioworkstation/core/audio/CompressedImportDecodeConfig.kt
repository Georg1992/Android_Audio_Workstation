package com.georgv.audioworkstation.core.audio

/**
 * Compressed decode input policy.
 *
 * MP3/MPEG frames from [android.media.MediaExtractor] are typically a few hundred bytes each.
 * Queueing one sample per MediaCodec input buffer forces thousands of dequeue/queue round-trips
 * and dominated import time before batching (~81s on test devices). Packing consecutive samples
 * into each codec input buffer (up to its capacity) cuts that overhead dramatically (~8s).
 *
 * Batching is enabled only for MIME types verified to tolerate concatenated access units.
 * Other compressed formats use one sample per input buffer.
 */
internal object CompressedImportDecodeConfig {
    const val COMPRESSED_INPUT_BATCHING_ENABLED = true

    private val BATCHING_MIME_TYPES =
        setOf(
            "audio/mpeg",
            "audio/mp3",
        )

    fun batchingEnabledFor(mimeType: String): Boolean =
        COMPRESSED_INPUT_BATCHING_ENABLED &&
            mimeType.lowercase() in BATCHING_MIME_TYPES
}
