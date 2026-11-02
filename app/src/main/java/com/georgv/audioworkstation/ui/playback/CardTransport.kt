package com.georgv.audioworkstation.ui.playback

data class CardTransport(
    val projectId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)
