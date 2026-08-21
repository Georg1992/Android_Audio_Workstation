package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.isTrackLoadedInSessionLane
import com.georgv.audioworkstation.core.audio.laneAudibilityFromSelection
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Owns Kotlin playback-session state for a single project screen.
 *
 * Scope changes rebuild the full transport spec via [ScopePlaybackCoordinator] — no hot-join path.
 */
class PlaybackSessionController(
    private val scope: CoroutineScope,
    private val playback: PlaybackPort,
    private val dispatchers: AppDispatchers,
    private val loadCurrentProject: suspend (String) -> ProjectEntity?,
    private val currentProjectId: () -> String?,
    private val visibleTracks: () -> List<TrackEntity>,
    private val onPlaybackCompleted: () -> Unit,
    private val suppressTransportOnPlaybackCompletion: () -> Boolean,
) {
    private val _sessionTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val sessionTrackIds: StateFlow<Set<String>> = _sessionTrackIds.asStateFlow()

    private val sessionLaneTrackIds: Array<String?> = arrayOfNulls(MultiPlaybackSpec.MaxLanes)

    private var playbackMonitorJob: kotlinx.coroutines.Job? = null

    private val _playbackSessionActive = MutableStateFlow(false)
    val playbackSessionActive: StateFlow<Boolean> = _playbackSessionActive.asStateFlow()

    private var playbackSessionEpoch = 0L

    fun isSessionSpecArmed(): Boolean = _sessionTrackIds.value.isNotEmpty()

    fun hasActivePlaybackSession(): Boolean = _playbackSessionActive.value

    fun currentAudibleTrackIds(selectedTrackIds: Set<String>): Set<String> =
        com.georgv.audioworkstation.core.audio.audibleTrackIds(selectedTrackIds, sessionLaneTrackIds)

    fun isTrackLoadedInSession(trackId: String): Boolean =
        _playbackSessionActive.value && isTrackLoadedInSessionLane(trackId, sessionLaneTrackIds)

    fun sessionLaneTrackIdSet(): Set<String> = sessionLaneTrackIds.filterNotNull().toSet()

    fun wouldLeaveNoSessionLaneSelected(selectedTrackIds: Set<String>, trackId: String): Boolean {
        if (!hasActivePlaybackSession() || trackId !in selectedTrackIds) return false
        val laneTracks = sessionLaneTrackIdSet()
        if (laneTracks.isEmpty()) return false
        return (selectedTrackIds - trackId).intersect(laneTracks).isEmpty()
    }

    fun resetWhenProjectChanges() {
        cancelCompletionMonitorForTransportStop()
        tearDownPlaybackSessionState(endTransport = false)
    }

    fun markPlayingAndStartCompletionMonitor(trackIdsInLaneOrder: List<String>) {
        startPlaybackSession(trackIdsInLaneOrder)
        startPlaybackMonitor(trackIdsInLaneOrder.toSet())
    }

    fun markPlayingAndStartCompletionMonitor(trackId: String) {
        markPlayingAndStartCompletionMonitor(listOf(trackId))
    }

    fun sessionLaneTrackIdsForTests(): Array<String?> = sessionLaneTrackIds.copyOf()

    fun advancePlaybackSessionEpochForTests() {
        playbackSessionEpoch++
    }

    fun cancelCompletionMonitorForTransportStop() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
    }

    suspend fun stopEngineIfMarkedPlaying() {
        if (_sessionTrackIds.value.isNotEmpty()) {
            withAudioIo(dispatchers, "PlaybackPort.stopPlayback") {
                playback.stopPlayback()
            }
        }
    }

    suspend fun pauseEnginePreservingSession() {
        cancelCompletionMonitorForTransportStop()
        stopEngineIfMarkedPlaying()
    }

    suspend fun restartEngineFromPlayhead(
        spec: MultiPlaybackSpec,
        trackIdsInLaneOrder: List<String>,
        @Suppress("UNUSED_PARAMETER") selectedTrackIds: Set<String>,
    ): Boolean {
        if (!_playbackSessionActive.value || trackIdsInLaneOrder.isEmpty()) return false
        cancelCompletionMonitorForTransportStop()
        clearNativeLaneSessionState()
        stopEngineIfMarkedPlaying()
        playbackSessionEpoch++
        refreshSessionLaneMappings(trackIdsInLaneOrder)
        _sessionTrackIds.value = trackIdsInLaneOrder.toSet()
        val started =
            withAudioIo(dispatchers, "PlaybackPort.startPlayback seekRestart") {
                playback.startPlayback(spec)
            }
        if (!started) return false
        startPlaybackMonitor(trackIdsInLaneOrder.toSet())
        return true
    }

    fun clearPlayingTransportState() {
        cancelCompletionMonitorForTransportStop()
        tearDownPlaybackSessionState(endTransport = false)
    }

    private fun startPlaybackSession(trackIdsInLaneOrder: List<String>) {
        playbackSessionEpoch++
        _playbackSessionActive.value = true
        refreshSessionLaneMappings(trackIdsInLaneOrder)
        _sessionTrackIds.value = trackIdsInLaneOrder.toSet()
    }

    private fun clearNativeLaneSessionState() {
        for (laneIndex in sessionLaneTrackIds.indices) {
            if (sessionLaneTrackIds[laneIndex] == null) continue
            playback.setPlaybackLaneAudible(laneIndex, false)
            playback.cancelHotJoinLane(laneIndex)
        }
    }

    private fun tearDownPlaybackSessionState(endTransport: Boolean) {
        playbackSessionEpoch++
        _playbackSessionActive.value = false
        clearNativeLaneSessionState()
        refreshSessionLaneMappings(emptyList())
        _sessionTrackIds.value = emptySet()
        if (endTransport) {
            onPlaybackCompleted()
        }
    }

    private fun refreshSessionLaneMappings(trackIdsInLaneOrder: List<String>) {
        for (index in sessionLaneTrackIds.indices) {
            sessionLaneTrackIds[index] = null
        }
        trackIdsInLaneOrder.forEachIndexed { index, trackId ->
            if (index < sessionLaneTrackIds.size) {
                sessionLaneTrackIds[index] = trackId
            }
        }
    }

    fun livePlaybackLaneIndexForTrack(trackId: String): Int? =
        sessionLaneTrackIds.indexOfFirst { it == trackId }.takeIf { it >= 0 }

    fun syncLaneAudibilityFromSelection(selectedTrackIds: Set<String>) {
        playback.setArmedPlaybackLaneAudibility(
            laneAudibilityFromSelection(sessionLaneTrackIds, selectedTrackIds),
        )
    }

    private fun startPlaybackMonitor(trackIds: Set<String>) {
        playbackMonitorJob?.cancel()
        playbackMonitorJob =
            scope.launch {
                val monitorEpoch = playbackSessionEpoch
                while (_playbackSessionActive.value && monitorEpoch == playbackSessionEpoch) {
                    playback.playbackState.filter { it }.first()
                    if (!_playbackSessionActive.value || monitorEpoch != playbackSessionEpoch) break
                    playback.playbackState.filter { !it }.first()
                    if (!_playbackSessionActive.value || monitorEpoch != playbackSessionEpoch) break
                    if (_sessionTrackIds.value != trackIds) break
                    if (suppressTransportOnPlaybackCompletion()) {
                        tearDownPlaybackSessionState(endTransport = false)
                        break
                    }
                    tearDownPlaybackSessionState(endTransport = true)
                    break
                }
                playbackMonitorJob = null
            }
    }
}
