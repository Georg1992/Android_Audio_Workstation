package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.ui.playback.CardTransport
import com.georgv.audioworkstation.ui.playback.SharedMixStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCardPlayback @Inject constructor() : CardPlayback {
    private val lock = Any()
    private val stream = SharedMixStream()
    private var projectId: String? = null
    private var active = false

    override fun play(projectId: String, url: String) {
        stream.play(url)
        synchronized(lock) {
            this.projectId = projectId
            active = true
        }
    }

    override fun stop() {
        synchronized(lock) {
            active = false
        }
        stream.stop()
    }

    override fun seekTo(positionMs: Int) {
        stream.seekTo(positionMs)
    }

    override fun release() {
        synchronized(lock) {
            active = false
        }
        stream.release()
    }

    override fun read(): CardTransport {
        val state = stream.transport()
        synchronized(lock) {
            if (!state.playing) active = false
            val id = projectId ?: return CardTransport()
            return CardTransport(
                projectId = id,
                playing = active && state.playing,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
            )
        }
    }
}
