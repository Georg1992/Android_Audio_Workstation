package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.ui.playback.CardTransport

interface CardPlayback {
    fun play(projectId: String, url: String)

    fun stop()

    /** Pause the output while the finger is down. The card stays on this mix. */
    fun hold()

    fun seekTo(positionMs: Int)

    fun read(): CardTransport

    fun pullFailure(): Exception?

    fun release()
}
