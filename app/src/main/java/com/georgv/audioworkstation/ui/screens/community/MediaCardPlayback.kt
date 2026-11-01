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
    private var active = false
    private var pendingPositionMs: Int? = null
    private var seekSerial = 0

    override fun play(projectId: String, file: File) {
        synchronized(lock) {
            releaseLocked()
            val created = MediaPlayer()
            created.setDataSource(file.absolutePath)
            created.setOnCompletionListener {
                synchronized(lock) {
                    if (player === created) {
                        active = false
                        pendingPositionMs = 0
                        seekSerial += 1
                        created.pause()
                        created.seekTo(0)
                    }
                }
            }
            created.prepare()
            created.start()
            player = created
            this.projectId = projectId
            active = true
            pendingPositionMs = null
        }
    }

    override fun stop() {
        synchronized(lock) {
            val current = player ?: return
            active = false
            pendingPositionMs = 0
            seekSerial += 1
            current.pause()
            current.seekTo(0)
        }
    }

    /**
     * Moves the playhead to [positionMs]. A seek pauses [MediaPlayer], so playback that is
     * still active starts again when that seek finishes.
     */
    override fun seekTo(positionMs: Int) {
        synchronized(lock) {
            val current = player ?: return
            val duration = current.duration
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            pendingPositionMs = target
            val serial = ++seekSerial
            current.setOnSeekCompleteListener { completed ->
                synchronized(lock) {
                    if (player !== completed || serial != seekSerial) return@synchronized
                    pendingPositionMs = null
                    if (active) completed.start()
                }
            }
            current.seekTo(target)
        }
    }

    override fun read(): CardTransport {
        synchronized(lock) {
            val current = player
            val id = projectId
            if (current == null || id == null) return CardTransport()
            val pending = pendingPositionMs
            return CardTransport(
                projectId = id,
                playing = active,
                positionMs = if (pending != null) pending else current.currentPosition,
                durationMs = current.duration,
            )
        }
    }

    private fun releaseLocked() {
        val current = player
        seekSerial += 1
        player = null
        projectId = null
        active = false
        pendingPositionMs = null
        current?.setOnSeekCompleteListener(null)
        current?.release()
    }
}
