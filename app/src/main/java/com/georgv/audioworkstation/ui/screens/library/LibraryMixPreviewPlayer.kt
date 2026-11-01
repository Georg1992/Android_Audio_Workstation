package com.georgv.audioworkstation.ui.screens.library

import android.media.MediaPlayer
import com.georgv.audioworkstation.core.util.logWarning
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single shared WAV preview for Library project cards.
 */
@Singleton
class LibraryMixPreviewPlayer @Inject constructor() {
    private val mediaPlayerLock = Any()
    private var mediaPlayer: MediaPlayer? = null
    private var loadedProjectId: String? = null
    private var pendingPositionMs: Int? = null
    private var seekSerial = 0

    private val playingProjectId = MutableStateFlow<String?>(null)
    val playingProjectIdState: StateFlow<String?> = playingProjectId.asStateFlow()

    private val transport = MutableStateFlow(MixTransport())
    val transportState: StateFlow<MixTransport> = transport.asStateFlow()

    fun isPlaying(projectId: String): Boolean = playingProjectId.value == projectId

    /**
     * Starts preview for [projectId], or stops if it is already playing.
     *
     * @return true when playback started, false when stopped or failed.
     */
    fun togglePreview(projectId: String, wavPath: String): Boolean {
        synchronized(mediaPlayerLock) {
            if (playingProjectId.value == projectId) {
                stopLocked()
                return false
            }
            stopLocked()
            return startLocked(projectId, wavPath)
        }
    }

    fun play(projectId: String, wavPath: String): Boolean {
        synchronized(mediaPlayerLock) {
            val current = mediaPlayer
            if (current != null && loadedProjectId == projectId) {
                pendingPositionMs = null
                seekSerial += 1
                current.setOnSeekCompleteListener(null)
                current.start()
                playingProjectId.value = projectId
                publishLocked()
                return true
            }
            releaseLocked()
            return startLocked(projectId, wavPath)
        }
    }

    fun stop() {
        synchronized(mediaPlayerLock) {
            val current = mediaPlayer ?: return
            playingProjectId.value = null
            pendingPositionMs = 0
            seekSerial += 1
            current.pause()
            current.seekTo(0)
            publishLocked()
        }
    }

    /**
     * Moves the playhead to [positionMs]. A seek pauses [MediaPlayer], so a session that is
     * still playing starts again when that seek finishes.
     */
    fun seekTo(positionMs: Int) {
        synchronized(mediaPlayerLock) {
            val current = mediaPlayer ?: return
            val duration = current.duration
            val target = if (duration > 0) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
            pendingPositionMs = target
            val serial = ++seekSerial
            current.setOnSeekCompleteListener { completed ->
                synchronized(mediaPlayerLock) {
                    if (mediaPlayer !== completed || serial != seekSerial) return@synchronized
                    pendingPositionMs = null
                    if (playingProjectId.value != null) completed.start()
                    publishLocked()
                }
            }
            current.seekTo(target)
            publishLocked()
        }
    }

    fun tick() {
        synchronized(mediaPlayerLock) {
            publishLocked()
        }
    }

    fun stopIfPlaying(projectId: String) {
        synchronized(mediaPlayerLock) {
            if (playingProjectId.value == projectId) {
                stopLocked()
            }
        }
    }

    fun stopAll() {
        synchronized(mediaPlayerLock) {
            stopLocked()
        }
    }

    private fun startLocked(projectId: String, wavPath: String): Boolean {
        val file = File(wavPath)
        if (!file.isFile) return false
        return try {
            val player = MediaPlayer()
            mediaPlayer = player
            player.setDataSource(wavPath)
            player.setOnCompletionListener {
                synchronized(mediaPlayerLock) {
                    if (mediaPlayer === player) {
                        playingProjectId.value = null
                        pendingPositionMs = 0
                        seekSerial += 1
                        player.pause()
                        player.seekTo(0)
                        publishLocked()
                    }
                }
            }
            player.prepare()
            player.start()
            loadedProjectId = projectId
            playingProjectId.value = projectId
            publishLocked()
            true
        } catch (error: Exception) {
            logWarning(TAG, "play failed: $projectId", error)
            releaseLocked()
            false
        }
    }

    private fun stopLocked() {
        releaseLocked()
    }

    private fun releaseLocked() {
        seekSerial += 1
        pendingPositionMs = null
        runCatching { mediaPlayer?.setOnSeekCompleteListener(null) }
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        loadedProjectId = null
        playingProjectId.value = null
        publishLocked()
    }

    private fun publishLocked() {
        transport.value = snapshotLocked()
    }

    private fun snapshotLocked(): MixTransport {
        val current = mediaPlayer
        val id = loadedProjectId
        if (current == null || id == null) return MixTransport()
        val pending = pendingPositionMs
        return MixTransport(
            projectId = id,
            playing = playingProjectId.value != null,
            positionMs = if (pending != null) pending else current.currentPosition,
            durationMs = current.duration,
        )
    }

    private companion object {
        const val TAG = "LibraryMixPreviewPlayer"
    }
}

data class MixTransport(
    val projectId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)
