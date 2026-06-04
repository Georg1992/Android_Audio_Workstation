package com.georgv.audioworkstation.core.audio

data class AudioImportProgressUpdate(
    val fraction: Float,
    val decodedDurationMs: Long,
)
