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

    private val playingProjectId = MutableStateFlow<String?>(null)
    val playingProjectIdState: StateFlow<String?> = playingProjectId.asStateFlow()

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
            val file = File(wavPath)
            if (!file.isFile) return false
            return try {
                val player = MediaPlayer()
                player.setDataSource(wavPath)
                player.setOnCompletionListener {
                    synchronized(mediaPlayerLock) {
                        if (playingProjectId.value == projectId) {
                            releaseLocked()
                        }
                    }
                }
                player.prepare()
                player.start()
                mediaPlayer = player
                playingProjectId.value = projectId
                true
            } catch (error: Exception) {
                logWarning(TAG, "togglePreview failed: $projectId", error)
                releaseLocked()
                false
            }
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

    private fun stopLocked() {
        releaseLocked()
    }

    private fun releaseLocked() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        playingProjectId.value = null
    }

    private companion object {
        const val TAG = "LibraryMixPreviewPlayer"
    }
}
