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
            current.pause()
            current.seekTo(0)
            playingProjectId.value = null
            publishLocked()
        }
    }

    fun seekTo(positionMs: Int) {
        synchronized(mediaPlayerLock) {
            val current = mediaPlayer ?: return
            current.seekTo(positionMs)
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
                        player.pause()
                        player.seekTo(0)
                        playingProjectId.value = null
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
        return MixTransport(
            projectId = id,
            playing = current.isPlaying,
            positionMs = current.currentPosition,
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
