package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.ui.diagnostics.ThreadingDiagnostics
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackLaneLifecycle
import com.georgv.audioworkstation.core.audio.audibleTrackIds
import com.georgv.audioworkstation.core.audio.shouldHotJoinTrackAtTransport
import com.georgv.audioworkstation.core.audio.toHotJoinLaneSpec
import com.georgv.audioworkstation.core.audio.isTrackLoadedInSessionLane
import com.georgv.audioworkstation.core.audio.laneAudibilityFromSelection
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Owns Kotlin playback-session state for a single project screen.
 *
 * **State ownership (Clock.1):**
 * - [selectedTrackIds] (ViewModel): UI audible intent; not lane ownership.
 * - [sessionTrackIds]: tracks in the current transport [MultiPlaybackSpec] (play, Seek.1 restart, loop).
 *   Excludes hot-joined-only tracks.
 * - [sessionLaneTrackIds]: native lane index → track for every loaded session lane (arm + hot-join).
 * - [preparingTrackIds]: hot-join prepare/commit in flight.
 * - [playbackSessionActive]: Kotlin session lifecycle (maps/monitors valid).
 * - [audibleTrackIds] (derived): `selectedTrackIds ∩ sessionLaneTrackIds.values` — see
 *   [currentAudibleTrackIds].
 * - [audioController.playbackState]: native engine actively playing (polled).
 *
 * Live selection runs only when [playbackSessionActive] and [audioController.playbackState] are true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionController(
    private val scope: CoroutineScope,
    private val audioController: AudioController,
    private val dispatchers: AppDispatchers,
    private val loadCurrentProject: suspend (String) -> ProjectEntity?,
    private val currentProjectId: () -> String?,
    private val visibleTracks: () -> List<TrackEntity>,
    private val onPlaybackCompleted: () -> Unit = {},
    private val suppressTransportOnPlaybackCompletion: () -> Boolean = { false },
    private val onHotJoinFailed: () -> Unit = {},
) {
    private val _sessionTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val sessionTrackIds: StateFlow<Set<String>> = _sessionTrackIds.asStateFlow()

    private val _preparingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    val preparingTrackIds: StateFlow<Set<String>> = _preparingTrackIds.asStateFlow()

    private val sessionLaneTrackIds: Array<String?> = arrayOfNulls(MultiPlaybackSpec.MaxLanes)
    private val preparingLaneIndexByTrackId = mutableMapOf<String, Int>()
    private val hotJoinMonitorJobs = mutableMapOf<String, Job>()

    private var playbackMonitorJob: Job? = null

    private val _playbackSessionActive = MutableStateFlow(false)
    val playbackSessionActive: StateFlow<Boolean> = _playbackSessionActive.asStateFlow()

    /** Bumped on session start and teardown — hot-join monitors must match to write lanes. */
    private var playbackSessionEpoch = 0L

    /** True when [sessionTrackIds] is non-empty (transport spec armed). */
    fun isSessionSpecArmed(): Boolean = _sessionTrackIds.value.isNotEmpty()

    fun hasActivePlaybackSession(): Boolean = _playbackSessionActive.value

    /**
     * Tracks the user would hear: selected intent intersected with loaded session lanes.
     * Not stored — recompute when needed.
     */
    fun currentAudibleTrackIds(selectedTrackIds: Set<String>): Set<String> =
        audibleTrackIds(selectedTrackIds, sessionLaneTrackIds)

    fun isTrackLoadedInSession(trackId: String): Boolean =
        _playbackSessionActive.value && isTrackLoadedInSessionLane(trackId, sessionLaneTrackIds)

    fun sessionLaneTrackIdSet(): Set<String> = sessionLaneTrackIds.filterNotNull().toSet()

    /**
     * True when removing [trackId] from [selectedTrackIds] would leave no selected tracks on loaded
     * session lanes while [hasActivePlaybackSession].
     */
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

    /**
     * HJ.1 + HJ.2: live audibility, hot-join, and cancel-prepare while a playback session is active
     * and the native engine is still playing.
     */
    fun onSelectionChangedDuringPlayback(
        selectedTrackIds: Set<String>,
        playableTracks: List<TrackEntity>,
    ) {
        if (!_playbackSessionActive.value || !audioController.isPlaybackEngineRunning()) {
            return
        }

        val preparingSnapshot = _preparingTrackIds.value
        for (trackId in preparingSnapshot) {
            if (trackId !in selectedTrackIds) {
                cancelHotJoinForTrack(trackId)
            }
        }

        syncLaneAudibilityFromSelection(selectedTrackIds)

        for (track in playableTracks) {
            if (track.id !in selectedTrackIds) continue
            if (trackLaneIndex(track.id) != null) continue
            if (track.id in _preparingTrackIds.value) continue
            startHotJoinForTrack(track, selectedTrackIds)
        }
    }

    fun sessionLaneTrackIdsForTests(): Array<String?> = sessionLaneTrackIds.copyOf()

    fun hotJoinMonitorCountForTests(): Int = hotJoinMonitorJobs.size

    fun advancePlaybackSessionEpochForTests() {
        playbackSessionEpoch++
    }

    fun cancelCompletionMonitorForTransportStop() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
    }

    suspend fun stopEngineIfMarkedPlaying() {
        if (_sessionTrackIds.value.isNotEmpty()) {
            withAudioIo(dispatchers, "AudioController.stopPlayback") {
                audioController.stopPlayback()
            }
        }
    }

    /**
     * Transport Seek.1: stop native playback and completion monitor without clearing session lane maps.
     */
    suspend fun pauseEnginePreservingSession() {
        cancelCompletionMonitorForTransportStop()
        stopEngineIfMarkedPlaying()
    }

    /**
     * Seek.1: restart native playback at [spec.startPositionMs] and rebuild session maps from
     * [trackIdsInLaneOrder] (current selected playable set), not stale hot-join-only lane state.
     */
    suspend fun restartEngineFromPlayhead(
        spec: MultiPlaybackSpec,
        trackIdsInLaneOrder: List<String>,
        selectedTrackIds: Set<String>,
    ): Boolean {
        if (!_playbackSessionActive.value || trackIdsInLaneOrder.isEmpty()) return false
        cancelCompletionMonitorForTransportStop()
        clearNativeLaneSessionState()
        stopEngineIfMarkedPlaying()
        playbackSessionEpoch++
        clearHotJoinState()
        refreshSessionLaneMappings(trackIdsInLaneOrder)
        _sessionTrackIds.value = trackIdsInLaneOrder.toSet()
        val started =
            withAudioIo(dispatchers, "AudioController.startPlayback seekRestart") {
                audioController.startPlayback(spec)
            }
        if (!started) return false
        syncLaneAudibilityFromSelection(selectedTrackIds)
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
        clearHotJoinState()
        refreshSessionLaneMappings(trackIdsInLaneOrder)
        _sessionTrackIds.value = trackIdsInLaneOrder.toSet()
    }

    /** Clears [sessionLaneTrackIds] only (native lanes cleared via [clearNativeLaneSessionState]). */
    private fun clearSessionLaneMappings() {
        refreshSessionLaneMappings(emptyList())
    }

    private fun clearPlaybackSessionMappings() {
        clearSessionLaneMappings()
    }

    /** Cancels hot-join monitor jobs and preparing bookkeeping. */
    private fun clearHotJoinState() {
        cancelAllHotJoinMonitors()
        _preparingTrackIds.value = emptySet()
        preparingLaneIndexByTrackId.clear()
    }

    /** Mutes and cancels native lanes that still map to this session (safe before [clearSessionLaneMappings]). */
    private fun clearNativeLaneSessionState() {
        for (laneIndex in sessionLaneTrackIds.indices) {
            if (sessionLaneTrackIds[laneIndex] == null) continue
            audioController.setPlaybackLaneAudible(laneIndex, false)
            audioController.cancelHotJoinLane(laneIndex)
        }
        for (laneIndex in preparingLaneIndexByTrackId.values) {
            audioController.setPlaybackLaneAudible(laneIndex, false)
            audioController.cancelHotJoinLane(laneIndex)
        }
    }

    /**
     * Clears session maps/monitors without cancelling the completion monitor job (safe from inside the monitor).
     * When [endTransport] is true, invokes [onPlaybackCompleted] (Stop-equivalent playhead reset in VM).
     */
    private fun tearDownPlaybackSessionState(endTransport: Boolean) {
        playbackSessionEpoch++
        _playbackSessionActive.value = false
        clearNativeLaneSessionState()
        clearHotJoinState()
        clearPlaybackSessionMappings()
        _sessionTrackIds.value = emptySet()
        if (endTransport) {
            onPlaybackCompleted()
        }
    }

    private fun refreshSessionLaneMappings(trackIdsInLaneOrder: List<String>) {
        for (index in sessionLaneTrackIds.indices) {
            sessionLaneTrackIds[index] = null
        }
        preparingLaneIndexByTrackId.clear()
        trackIdsInLaneOrder.forEachIndexed { index, trackId ->
            if (index < sessionLaneTrackIds.size) {
                sessionLaneTrackIds[index] = trackId
            }
        }
    }

    private fun cancelAllHotJoinMonitors() {
        for (trackId in hotJoinMonitorJobs.keys.toList()) {
            cancelHotJoinForTrack(trackId)
        }
    }

    private fun trackLaneIndex(trackId: String): Int? =
        sessionLaneTrackIds.indexOfFirst { it == trackId }.takeIf { it >= 0 }

    /**
     * Native lane index for live gain/audibility while a session is active, including hot-join
     * lanes that are still preparing.
     */
    fun livePlaybackLaneIndexForTrack(trackId: String): Int? =
        trackLaneIndex(trackId) ?: preparingLaneIndexByTrackId[trackId]

    private fun syncLaneAudibilityFromSelection(selectedTrackIds: Set<String>) {
        audioController.setArmedPlaybackLaneAudibility(
            laneAudibilityFromSelection(sessionLaneTrackIds, selectedTrackIds),
        )
    }

    /**
     * HJ.2 hot-join: arms a lane from track clip bounds and loop region metadata.
     * Timeline placement is enforced by native render/ioLoop — lane may commit before clip start
     * but stays silent until transport reaches [TrackEntity.timelineStartOffsetMs].
     */
    private fun startHotJoinForTrack(track: TrackEntity, selectedTrackIds: Set<String>) {
        val wavPath = track.wavFilePath
        if (wavPath.isBlank()) return

        val transportMs = audioController.transportPositionMs()
        if (!shouldHotJoinTrackAtTransport(track, transportMs)) {
            return
        }

        val laneSpec = track.toHotJoinLaneSpec()
        val laneIndex =
            audioController.beginHotJoinLane(
                wavFilePath = wavPath,
                gain = GainRange.toUnit(track.gain),
                timelineClipStartMs = laneSpec.timelineClipStartMs,
                timelineClipDurationMs = laneSpec.timelineClipDurationMs,
                loopEnabled = laneSpec.loopEnabled,
                loopSourceStartMs = laneSpec.loopSourceStartMs,
                loopSourceEndMs = laneSpec.loopSourceEndMs,
                pan = laneSpec.pan,
            )
        if (laneIndex < 0) {
            onHotJoinFailed()
            return
        }

        val sessionEpochAtJoin = playbackSessionEpoch
        preparingLaneIndexByTrackId[track.id] = laneIndex
        _preparingTrackIds.value = _preparingTrackIds.value + track.id

        hotJoinMonitorJobs[track.id]?.cancel()
        hotJoinMonitorJobs[track.id] =
            scope.launch(dispatchers.default) {
                ThreadingDiagnostics.logPollLoop("default hotJoin")
                try {
                    monitorHotJoinLaneUntilSettled(
                        trackId = track.id,
                        laneIndex = laneIndex,
                        sessionEpochAtJoin = sessionEpochAtJoin,
                        selectedTrackIds = selectedTrackIds,
                    )
                } catch (_: CancellationException) {
                    clearPreparingForTrack(track.id)
                    hotJoinMonitorJobs.remove(track.id)
                }
            }
    }

    private suspend fun monitorHotJoinLaneUntilSettled(
        trackId: String,
        laneIndex: Int,
        sessionEpochAtJoin: Long,
        selectedTrackIds: Set<String>,
    ) {
        while (coroutineContext.isActive) {
            if (sessionEpochAtJoin != playbackSessionEpoch) {
                finishHotJoinMonitor(trackId)
                return
            }
            when (hotJoinStepForLifecycle(audioController.playbackLaneLifecycle(laneIndex))) {
                HotJoinMonitorStep.Poll -> delay(HOT_JOIN_POLL_MS)
                HotJoinMonitorStep.Abort -> {
                    finishHotJoinMonitor(trackId)
                    return
                }
                HotJoinMonitorStep.Commit -> {
                    if (sessionEpochAtJoin != playbackSessionEpoch) {
                        finishHotJoinMonitor(trackId)
                        return
                    }
                    withContext(dispatchers.main) {
                        sessionLaneTrackIds[laneIndex] = trackId
                        syncLaneAudibilityFromSelection(selectedTrackIds)
                        finishHotJoinMonitor(trackId)
                    }
                    return
                }
            }
        }
    }

    private fun finishHotJoinMonitor(trackId: String) {
        clearPreparingForTrack(trackId)
        hotJoinMonitorJobs.remove(trackId)
    }

    private enum class HotJoinMonitorStep {
        Poll,
        Commit,
        Abort,
    }

    private fun hotJoinStepForLifecycle(lifecycle: PlaybackLaneLifecycle): HotJoinMonitorStep =
        when (lifecycle) {
            PlaybackLaneLifecycle.Active,
            PlaybackLaneLifecycle.Exhausted,
            -> HotJoinMonitorStep.Commit
            PlaybackLaneLifecycle.Inactive,
            PlaybackLaneLifecycle.Cancelled,
            -> HotJoinMonitorStep.Abort
            PlaybackLaneLifecycle.Preparing,
            PlaybackLaneLifecycle.ReadyToCommit,
            -> HotJoinMonitorStep.Poll
        }

    private fun clearPreparingForTrack(trackId: String) {
        preparingLaneIndexByTrackId.remove(trackId)
        _preparingTrackIds.value = _preparingTrackIds.value - trackId
    }

    private fun cancelHotJoinForTrack(trackId: String) {
        hotJoinMonitorJobs.remove(trackId)?.cancel()
        val laneIndex = preparingLaneIndexByTrackId.remove(trackId) ?: trackLaneIndex(trackId)
        if (laneIndex != null) {
            audioController.setPlaybackLaneAudible(laneIndex, false)
            audioController.cancelHotJoinLane(laneIndex)
        }
        _preparingTrackIds.value = _preparingTrackIds.value - trackId
    }

    private fun startPlaybackMonitor(trackIds: Set<String>) {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = scope.launch {
            val monitorEpoch = playbackSessionEpoch
            while (_playbackSessionActive.value && monitorEpoch == playbackSessionEpoch) {
                audioController.playbackState.filter { it }.first()
                if (!_playbackSessionActive.value || monitorEpoch != playbackSessionEpoch) break
                audioController.playbackState.filter { !it }.first()
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

    private companion object {
        const val HOT_JOIN_POLL_MS = 5L
    }
}
