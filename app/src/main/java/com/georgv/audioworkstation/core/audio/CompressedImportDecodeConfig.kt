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

    /**
     * Upper bound on bytes packed per codec input buffer.
     * Effective batch size is [effectiveBatchMaxBytes] = min(codec capacity, safe max).
     * 256 KiB is large enough for codecs that expose 128–256 KiB input buffers while
     * bounding scratch memory on pathological capacities.
     */
    const val SAFE_MAX_BATCH_BYTES = 256 * 1024

    /**
     * Debug A/B only — keep [DEBUG_USE_CODEC_CAPACITY_ONLY] true for release behavior.
     * Set [debugSafeMaxBatchBytesOverride] locally to 8192, 16384, 65536, 131072, or 262144.
     * Set [DEBUG_USE_CODEC_CAPACITY_ONLY] to true to use codec capacity with no safety clamp.
     */
    internal const val DEBUG_USE_CODEC_CAPACITY_ONLY = false
    internal const val debugSafeMaxBatchBytesOverride: Int = SAFE_MAX_BATCH_BYTES

    /** Ratio of fill / effective max above which a buffer counts as near-full in diagnostics. */
    const val NEAR_FULL_FILL_RATIO = 0.95f

    private val BATCHING_MIME_TYPES =
        setOf(
            "audio/mpeg",
            "audio/mp3",
        )

    fun batchingEnabledFor(mimeType: String): Boolean =
        COMPRESSED_INPUT_BATCHING_ENABLED &&
            mimeType.lowercase() in BATCHING_MIME_TYPES

    fun safeMaxBatchBytes(): Int =
        when {
            DEBUG_USE_CODEC_CAPACITY_ONLY -> Int.MAX_VALUE
            else -> debugSafeMaxBatchBytesOverride
        }

    /** Hard per-buffer packing limit: codec capacity clamped by [safeMaxBatchBytes]. */
    fun effectiveBatchMaxBytes(codecInputCapacity: Int): Int =
        minOf(codecInputCapacity.coerceAtLeast(0), safeMaxBatchBytes())
}

/** Why a batched (or single-sample) input buffer stopped accepting more compressed data. */
internal enum class InputBufferFillStopReason {
    NEAR_FULL,
    END_OF_STREAM,
    NEXT_SAMPLE_WOULD_NOT_FIT,
    NON_MONOTONIC_TIMESTAMP,
    SINGLE_SAMPLE_MODE,
    SAFETY_CAP_REACHED,
}
