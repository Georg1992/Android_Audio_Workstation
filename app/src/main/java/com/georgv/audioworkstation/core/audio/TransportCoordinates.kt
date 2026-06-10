package com.georgv.audioworkstation.core.audio

/**
 * Audible timeline position — what the user hears and the global ruler shows.
 * Never pass directly to native arm/seek; convert via [PlaybackTransportSync] first.
 */
@JvmInline
value class AudibleMs(val value: Long) {
    init {
        require(value >= 0L) { "Audible ms must be non-negative" }
    }

    fun coerceAtLeast(min: Long): AudibleMs = AudibleMs(value.coerceAtLeast(min))
}

/**
 * Native mix transport position — raw engine clock from [AudioController.transportPositionMs].
 * Used for arm/seek/rebuild and in-clip waveform source mapping.
 */
@JvmInline
value class MixTransportMs(val value: Long) {
    init {
        require(value >= 0L) { "Mix transport ms must be non-negative" }
    }

    fun coerceAtLeast(min: Long): MixTransportMs = MixTransportMs(value.coerceAtLeast(min))
}
