package com.georgv.audioworkstation.ui.screens.community

import java.io.File

interface CardPlayback {
    fun play(projectId: String, file: File)

    fun stop()

    fun seekTo(positionMs: Int)

    fun read(): CardTransport
}

data class CardTransport(
    val projectId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)
