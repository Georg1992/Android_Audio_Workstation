package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.ui.playback.CardTransport

interface CardPlayback {
    fun play(projectId: String, url: String)

    fun stop()

    fun seekTo(positionMs: Int)

    fun read(): CardTransport

    fun release()
}
