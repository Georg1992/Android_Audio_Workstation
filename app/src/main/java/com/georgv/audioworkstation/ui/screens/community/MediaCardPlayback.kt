package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.ui.playback.MemoryPcmPreview
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCardPlayback @Inject constructor() : CardPlayback {
    private val lock = Any()
    private val memory = MemoryPcmPreview()
    private var projectId: String? = null
    private var active = false

    override fun play(projectId: String, file: File) {
        if (!memory.load(file.absolutePath)) error("mix preview failed")
        synchronized(lock) {
            this.projectId = projectId
            active = true
            memory.play()
        }
    }

    override fun stop() {
        synchronized(lock) {
            active = false
            memory.stop()
        }
    }

    override fun seekTo(positionMs: Int) {
        memory.seekTo(positionMs)
    }

    override fun read(): CardTransport {
        val state = memory.transport()
        synchronized(lock) {
            if (state.ended) active = false
            val id = projectId ?: return CardTransport()
            return CardTransport(
                projectId = id,
                playing = active,
                positionMs = state.positionMs,
                durationMs = state.durationMs,
            )
        }
    }
}
