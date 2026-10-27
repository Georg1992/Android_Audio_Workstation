package com.georgv.audioworkstation.ui.screens.community

import android.media.MediaPlayer
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaCardPlayback @Inject constructor() : CardPlayback {
    private val lock = Any()
    private var player: MediaPlayer? = null
    private var projectId: String? = null

    override fun play(projectId: String, file: File) {
        synchronized(lock) {
            releaseLocked()
            val created = MediaPlayer()
            created.setDataSource(file.absolutePath)
            created.setOnCompletionListener {
                synchronized(lock) {
                    if (player === created) {
                        created.pause()
                        created.seekTo(0)
                    }
                }
            }
            created.prepare()
            created.start()
            player = created
            this.projectId = projectId
        }
    }

    override fun stop() {
        synchronized(lock) {
            val current = player ?: return
            current.pause()
            current.seekTo(0)
        }
    }

    override fun seekTo(positionMs: Int) {
        synchronized(lock) {
            val current = player ?: return
            current.seekTo(positionMs)
        }
    }

    override fun read(): CardTransport {
        synchronized(lock) {
            val current = player
            val id = projectId
            if (current == null || id == null) return CardTransport()
            return CardTransport(
                projectId = id,
                playing = current.isPlaying,
                positionMs = current.currentPosition,
                durationMs = current.duration,
            )
        }
    }

    private fun releaseLocked() {
        val current = player
        player = null
        projectId = null
        current?.release()
    }
}
