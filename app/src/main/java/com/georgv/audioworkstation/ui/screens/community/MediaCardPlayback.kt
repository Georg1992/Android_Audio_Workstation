package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.core.audio.convert.androidAacCodec
import com.georgv.audioworkstation.ui.playback.AacMixSource
import com.georgv.audioworkstation.ui.playback.CardTransport
import com.georgv.audioworkstation.ui.playback.SharedMixStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCardPlayback @Inject constructor() : CardPlayback {
    private val lock = Any()
    private val stream = SharedMixStream(AacMixSource(codec = androidAacCodec()), keepSong = true)
    private var projectId: String? = null
    private var active = false
    private var scrubbing = false

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
            scrubbing = false
        }
        stream.stop()
    }

    override fun hold() {
        val pausing = synchronized(lock) { active }
        if (!pausing) return
        synchronized(lock) { scrubbing = true }
        stream.hold()
    }

    override fun seekTo(positionMs: Int) {
        stream.restartAt(positionMs)
        synchronized(lock) { scrubbing = false }
    }

    override fun release() {
        synchronized(lock) {
            active = false
            scrubbing = false
        }
        stream.release()
    }

    override fun pullFailure(): Exception? = stream.pullFailure()

    override fun read(): CardTransport {
        val state = stream.transport()
        synchronized(lock) {
            val scrub = scrubbing && active
            if (!state.playing && !scrub) active = false
            val id = projectId ?: return CardTransport()
            return CardTransport(
                projectId = id,
                playing = active && (state.playing || scrub),
                positionMs = state.positionMs,
                durationMs = state.durationMs,
            )
        }
    }
}
