package com.georgv.audioworkstation.core.audio

/** Native offline bounce using the same mixer path as live playback. */
interface MixdownPort {
    suspend fun renderOfflineMixdown(
        spec: MultiPlaybackSpec,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): MixdownResult

    fun cancelOfflineMixdown()
}
