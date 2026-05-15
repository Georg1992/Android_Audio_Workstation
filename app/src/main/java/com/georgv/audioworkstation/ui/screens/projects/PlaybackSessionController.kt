package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.toPlaybackSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns playback transport state for a single project screen: which track is marked playing and the
 * coroutine observing [AudioController.playbackState] for completion, loop restart, and teardown.
 *
 * Phase 1: extracted from [ProjectViewModel]; stop ordering remains orchestrated by the ViewModel via
 * [cancelCompletionMonitorForTransportStop], [stopEngineIfMarkedPlaying], and [clearPlayingTransportState].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionController(
    private val scope: CoroutineScope,
    private val audioController: AudioController,
    private val loadCurrentProject: suspend (String) -> ProjectEntity?,
    private val currentProjectId: () -> String?,
    private val visibleTracks: () -> List<TrackEntity>,
) {
    private val _playingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val playingTrackIds: StateFlow<Set<String>> = _playingTrackIds.asStateFlow()

    private var playbackMonitorJob: Job? = null

    fun isMarkedPlaying(): Boolean = _playingTrackIds.value.isNotEmpty()

    /**
     * When the bound project changes, cancel monitoring and drop playing markers (matches legacy [ProjectViewModel.bind]).
     */
    fun resetWhenProjectChanges() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
        _playingTrackIds.value = emptySet()
    }

    /** Native engine accepted a start request; expose playing id and observe completion / loop (legacy [ProjectViewModel] path). */
    fun markPlayingAndStartCompletionMonitor(trackId: String) {
        _playingTrackIds.value = setOf(trackId)
        startPlaybackMonitor(trackId)
    }

    fun cancelCompletionMonitorForTransportStop() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
    }

    fun stopEngineIfMarkedPlaying() {
        if (_playingTrackIds.value.isNotEmpty()) {
            audioController.stopPlayback()
        }
    }

    fun clearPlayingTransportState() {
        _playingTrackIds.value = emptySet()
    }

    private fun startPlaybackMonitor(trackId: String) {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = scope.launch {
            // The engine reports completion via [AudioController.playbackState]; we wait for it
            // to transition to false and then either restart (loop) or clear the playing set.
            while (true) {
                audioController.playbackState.filter { !it }.first()
                if (_playingTrackIds.value != setOf(trackId)) break
                if (!shouldRestartLoopPlayback(trackId)) {
                    _playingTrackIds.value = emptySet()
                    break
                }
            }
            playbackMonitorJob = null
        }
    }

    private suspend fun shouldRestartLoopPlayback(trackId: String): Boolean {
        val currentTrack = visibleTracks().find { it.id == trackId } ?: return false
        if (!currentTrack.isLoop) return false
        val pid = currentProjectId() ?: return false
        val currentProject = loadCurrentProject(pid) ?: return false
        val spec = currentProject.toPlaybackSpec(currentTrack) ?: return false
        return audioController.startPlayback(spec)
    }
}
