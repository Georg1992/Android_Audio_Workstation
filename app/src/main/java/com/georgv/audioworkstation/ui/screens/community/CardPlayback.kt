package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.ui.playback.CardTransport
import java.io.File

interface CardPlayback {
    fun play(projectId: String, file: File)

    fun stop()

    fun seekTo(positionMs: Int)

    fun read(): CardTransport

    fun release()
}
