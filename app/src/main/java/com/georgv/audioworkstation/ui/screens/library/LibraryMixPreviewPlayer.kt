package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.ui.playback.MemoryPcmPreview
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Single shared WAV preview for Library project cards.
 * The mix is kept in memory so scrubbing moves the playhead immediately.
 */
@Singleton
class LibraryMixPreviewPlayer @Inject constructor() {
    private val mediaPlayerLock = Any()
    private val memory = MemoryPcmPreview()
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
        val stopCurrent = synchronized(mediaPlayerLock) { playingProjectId.value == projectId }
        if (stopCurrent) {
            synchronized(mediaPlayerLock) { releaseLocked() }
            return false
        }
        return play(projectId, wavPath)
    }

    fun play(projectId: String, wavPath: String): Boolean {
        val reuse = synchronized(mediaPlayerLock) {
            loadedProjectId == projectId && memory.transport().durationMs > 0
        }
        if (!reuse && !memory.load(wavPath)) return false
        return synchronized(mediaPlayerLock) {
            loadedProjectId = projectId
            playingProjectId.value = projectId
            memory.play()
            publishLocked()
            true
        }
    }

    fun stop() {
        synchronized(mediaPlayerLock) {
            playingProjectId.value = null
            memory.stop()
            publishLocked()
        }
    }

    fun seekTo(positionMs: Int) {
        memory.seekTo(positionMs)
        synchronized(mediaPlayerLock) { publishLocked() }
    }

    fun tick() {
        synchronized(mediaPlayerLock) {
            if (memory.transport().ended) playingProjectId.value = null
            publishLocked()
        }
    }

    fun stopIfPlaying(projectId: String) {
        synchronized(mediaPlayerLock) {
            if (playingProjectId.value == projectId) releaseLocked()
        }
    }

    fun stopAll() {
        synchronized(mediaPlayerLock) { releaseLocked() }
    }

    private fun releaseLocked() {
        memory.release()
        loadedProjectId = null
        playingProjectId.value = null
        publishLocked()
    }

    private fun publishLocked() {
        transport.value = snapshotLocked()
    }

    private fun snapshotLocked(): MixTransport {
        val id = loadedProjectId
        val state = memory.transport()
        if (id == null || state.durationMs <= 0) return MixTransport()
        return MixTransport(
            projectId = id,
            playing = playingProjectId.value != null,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
        )
    }
}

data class MixTransport(
    val projectId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)
