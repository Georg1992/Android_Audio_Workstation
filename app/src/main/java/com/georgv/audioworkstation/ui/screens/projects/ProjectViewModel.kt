package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.toUiMessage
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.toProjectNameUiMessage
import com.georgv.audioworkstation.core.validation.toTrackNameUiMessage
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.components.TimelineClip
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.components.WavWaveformPeakExtractor
import com.georgv.audioworkstation.ui.components.TimelineMinimumBaseDurationMs
import com.georgv.audioworkstation.ui.components.ActiveRecordingTimelineClip
import com.georgv.audioworkstation.ui.components.buildProjectTimelineProjection
import com.georgv.audioworkstation.ui.components.projectTimelineClips
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForTracks
import com.georgv.audioworkstation.ui.components.timelineBaseDurationMs
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs
import com.georgv.audioworkstation.ui.screens.projects.reorder.OptimisticTrackOrder
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

private data class ProjectScreenSnapshot(
    val projectId: String?,
    val project: ProjectEntity?,
    val tracks: List<TrackEntity>,
    val selectedTrackIds: Set<String>,
    val sessionTrackIds: Set<String>,
    val playbackSessionActive: Boolean,
    val recordingTrackId: String?,
    val recordingInputLevel: Float,
    val waveformStatesByTrackId: Map<String, WaveformState>,
    val isRecordingStartup: Boolean,
)

data class ProjectUiState(
    val projectId: String? = null,
    val project: ProjectEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val selectedTrackIds: Set<String> = emptySet(),
    val sessionTrackIds: Set<String> = emptySet(),
    val playbackSessionActive: Boolean = false,
    val recordingTrackId: String? = null,
    val recordingInputLevel: Float = 0f,
    val waveformStatesByTrackId: Map<String, WaveformState> = emptyMap(),
    val timelineClipsByTrackId: Map<String, TimelineClip> = emptyMap(),
    val timelineBaseDurationMs: Long = TimelineMinimumBaseDurationMs,
    val playheadPositionMs: Long = 0L,
    val transportPlaybackPhase: TransportPlaybackPhase = TransportPlaybackPhase.Idle,
    val isRecordingStartup: Boolean = false,
) {
    val isPlayEnabled: Boolean
        get() = selectedTrackIds.isNotEmpty()

    val isStopEnabled: Boolean
        get() =
            recordingTrackId != null ||
                isRecordingStartup ||
                transportPlaybackPhase != TransportPlaybackPhase.Idle

    val isTransportPlaying: Boolean
        get() = transportPlaybackPhase == TransportPlaybackPhase.Playing

    val stopButtonShowsPause: Boolean
        get() = transportPlaybackPhase == TransportPlaybackPhase.Playing
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val audioController: AudioController,
    private val audioImportCoordinator: ProjectAudioImportCoordinator,
    private val recordingCoordinator: ProjectRecordingCoordinator,
    private val waveformPeakExtractor: WavWaveformPeakExtractor,
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)

    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val waveformStatesByTrackId = MutableStateFlow<Map<String, WaveformState>>(emptyMap())
    private val playheadPositionMs = MutableStateFlow(0L)
    private val playheadTransport =
        PlayheadTransportController(
            scope = viewModelScope,
            playheadPositionMs = playheadPositionMs,
            nativeTransportPositionMs = { audioController.transportPositionMs() },
        )
    private val waveformPeakPathsByTrackId = mutableMapOf<String, String>()
    private val waveformExtractionsInFlight = mutableSetOf<String>()

    /**
     * Optimistic override for the on-screen track list.
     *
     * When non-null, this is the single source of truth that the UI renders (e.g. while the user
     * drags a track around). It gets cleared automatically once the DB observation reports the
     * same id ordering, after which the DB stream takes over again.
     */
    private val optimisticTracks = MutableStateFlow<List<TrackEntity>?>(null)
    private val optimisticTrackGains = MutableStateFlow<Map<String, Float>>(emptyMap())
    private val recordingSession =
        RecordingSessionController(
            scope = viewModelScope,
            audioController = audioController,
            recordingCoordinator = recordingCoordinator,
        )
    /** Serializes [persistTrackOrderToDb] so overlapping drops cannot apply DB writes in the wrong order. */
    private val trackOrderPersistMutex = Mutex()

    private val resolvedProject = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.observeProject(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val projectTracks = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeTracks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val playbackSession =
        PlaybackSessionController(
            scope = viewModelScope,
            audioController = audioController,
            loadCurrentProject = { pid -> loadCurrentProject(pid) },
            currentProjectId = { projectId.value },
            visibleTracks = {
                visibleTracksWithRecordingOptimistic(
                    projectTracks.value,
                    optimisticTracks.value,
                    recordingSession.optimisticRecordingTrack.value,
                    optimisticTrackGains.value,
                )
            },
            onPlaybackCompleted = {
                audioController.stopPlayback()
                playheadTransport.stopAndResetToZero()
            },
            onLoopRestart = { playheadTransport.onLoopRestart() },
            suppressTransportOnPlaybackCompletion = { recordingSession.hasActiveRecordingTake() },
            onHotJoinFailed = { viewModelScope.launch { emitMessage(R.string.error_playback_failed_to_start) } },
        )

    private val playAndRecordTransport =
        PlayAndRecordTransport(
            audioController = audioController,
            playbackSession = playbackSession,
        )

    private val transportController = ProjectTransportController(
        audioController = audioController,
        playbackSession = playbackSession,
        recordingSession = recordingSession,
        finalizeRecordingTrackAfterSuccessfulEngineStop = { trackId -> finalizeRecordingTrack(trackId) },
    )

    val userMessages = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            // Once the DB stream catches up to our optimistic order, drop the override so the
            // DB resumes being the single source of truth for subsequent updates.
            combine(projectTracks, optimisticTracks) { tracks, optimistic ->
                tracks to optimistic
            }.collect { (tracks, optimistic) ->
                if (optimistic != null && tracks.map { it.id } == optimistic.map { it.id }) {
                    optimisticTracks.value = null
                }
            }
        }
        viewModelScope.launch {
            combine(projectTracks, recordingSession.optimisticRecordingTrack) { tracks, optRecording ->
                tracks to optRecording
            }.collect { (tracks, optRecording) ->
                if (optRecording != null && tracks.any { it.id == optRecording.id }) {
                    recordingSession.clearOptimisticRecordingRow()
                }
            }
        }
        viewModelScope.launch {
            combine(projectTracks, optimisticTrackGains) { tracks, gains -> tracks to gains }
                .collect { (tracks, gains) ->
                    if (gains.isEmpty()) return@collect
                    val next =
                        gains.filter { (trackId, gain) ->
                            tracks.any { it.id == trackId && it.gain != gain }
                        }
                    if (next.size != gains.size) {
                        optimisticTrackGains.value = next
                    }
                }
        }
        viewModelScope.launch {
            combine(projectTracks, recordingSession.optimisticRecordingTrack) { tracks, optRecording ->
                visibleTracksWithRecordingOptimistic(
                    tracks,
                    optimisticTracks.value,
                    optRecording,
                    optimisticTrackGains.value,
                )
            }.collect { tracks ->
                refreshWaveformPeakRequests(tracks)
            }
        }
    }

    private fun emitMessage(message: UiMessage) {
        messages.trySend(message)
    }

    private fun emitMessage(@StringRes resId: Int) {
        emitMessage(UiMessage(resId))
    }

    private suspend inline fun runDbAction(
        @StringRes errorResId: Int,
        crossinline action: suspend () -> Unit
    ): Boolean {
        return try {
            action()
            true
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            // Room/SQLite surfaces its failures as RuntimeException subclasses (e.g.
            // SQLiteConstraintException), so a single `Exception` net catches both the IO and
            // database failures these helpers wrap.
            emitMessage(errorResId)
            false
        }
    }

    /** Base list for UI: reorder override, then Room; append optimistic recording only if that id is absent. */
    private fun visibleTracksWithRecordingOptimistic(
        projectTracksList: List<TrackEntity>,
        optimisticOrder: List<TrackEntity>?,
        optimisticRecording: TrackEntity?,
        optimisticGains: Map<String, Float> = emptyMap(),
    ): List<TrackEntity> {
        val base = optimisticOrder ?: projectTracksList
        val withRecording =
            if (optimisticRecording != null && base.none { it.id == optimisticRecording.id }) {
                base + optimisticRecording
            } else {
                base
            }
        if (optimisticGains.isEmpty()) return withRecording
        return withRecording.map { track ->
            val gain = optimisticGains[track.id] ?: return@map track
            track.copy(gain = gain)
        }
    }

    private suspend inline fun runDbActionWithRollback(
        @StringRes errorResId: Int,
        rollback: () -> Unit,
        crossinline action: suspend () -> Unit
    ) {
        try {
            action()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            rollback()
            emitMessage(errorResId)
        }
    }

    private val projectScreenSnapshot =
        combine(
            combine(projectId, resolvedProject) { pid, proj -> pid to proj },
            combine(projectTracks, optimisticTracks, recordingSession.optimisticRecordingTrack, optimisticTrackGains) {
                    projectTracksList,
                    optimisticOrder,
                    optimisticRecording,
                    optimisticGains,
                ->
                visibleTracksWithRecordingOptimistic(
                    projectTracksList,
                    optimisticOrder,
                    optimisticRecording,
                    optimisticGains,
                )
            },
            combine(
                selectedTrackIds,
                playbackSession.sessionTrackIds,
                playbackSession.playbackSessionActive,
            ) { selected, sessionTracks, sessionActive ->
                Triple(selected, sessionTracks, sessionActive)
            },
            combine(recordingSession.recordingTrackId, recordingSession.recordingStartup) { recordingId, startup ->
                recordingId to startup
            },
            combine(audioController.recordingInputLevel, waveformStatesByTrackId) { level, waveformStates ->
                level to waveformStates
            },
        ) { pidProject, tracks, selPlay, recStartup, meterWaveform ->
            val (pid, proj) = pidProject
            val (selected, sessionTracks, sessionActive) = selPlay
            val (recording, startup) = recStartup
            val (recordingInputLevel, waveformStates) = meterWaveform
            pid to ProjectScreenSnapshot(
                projectId = pid,
                project = proj,
                tracks = tracks,
                selectedTrackIds = selected,
                sessionTrackIds = sessionTracks,
                playbackSessionActive = sessionActive,
                recordingTrackId = recording,
                recordingInputLevel = recordingInputLevel.coerceIn(0f, 1f),
                waveformStatesByTrackId = waveformStates,
                isRecordingStartup = startup,
            )
        }

    val uiState: StateFlow<ProjectUiState> =
        combine(
            projectScreenSnapshot,
            playheadPositionMs,
            playheadTransport.phase,
        ) { snapshot, playheadMs, transportPhase ->
            val (_, screen) = snapshot
            val activeRecording = activeRecordingTimelineClip(screen, playheadMs, transportPhase)
            val timeline =
                buildProjectTimelineProjection(
                    tracks = screen.tracks,
                    waveformStatesByTrackId = screen.waveformStatesByTrackId,
                    activeRecording = activeRecording,
                    playheadPositionMs = playheadMs,
                )
            val displayPlayheadMs =
                if (transportPhase == TransportPlaybackPhase.Recording) {
                    playheadMs.coerceAtLeast(0L)
                } else {
                    timelinePlayheadClampedPositionMs(playheadMs, timeline.timelineDurationMs)
                }
            ProjectUiState(
                projectId = screen.projectId,
                project = screen.project,
                tracks = screen.tracks,
                selectedTrackIds = screen.selectedTrackIds,
                sessionTrackIds = screen.sessionTrackIds,
                playbackSessionActive = screen.playbackSessionActive,
                recordingTrackId = screen.recordingTrackId,
                recordingInputLevel = screen.recordingInputLevel,
                waveformStatesByTrackId = screen.waveformStatesByTrackId,
                timelineClipsByTrackId = timeline.clipsByLaneId,
                timelineBaseDurationMs = timeline.timelineDurationMs,
                playheadPositionMs = displayPlayheadMs,
                transportPlaybackPhase = transportPhase,
                isRecordingStartup = screen.isRecordingStartup,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    init {
        viewModelScope.launch {
            uiState
                .map { it.timelineBaseDurationMs to it.transportPlaybackPhase }
                .distinctUntilChanged()
                .collect { (baseDurationMs, _) ->
                    playheadTransport.setTimelineBaseDurationMs(baseDurationMs)
                }
        }
    }

    private fun activeRecordingTimelineClip(
        screen: ProjectScreenSnapshot,
        playheadMs: Long,
        transportPhase: TransportPlaybackPhase,
    ): ActiveRecordingTimelineClip? {
        val recordingId = screen.recordingTrackId ?: return null
        if (transportPhase != TransportPlaybackPhase.Recording) return null
        val recordingTrack =
            screen.tracks.find { it.id == recordingId } ?: return null
        val startOffsetMs = recordingTrack.timelineStartOffsetMs.coerceAtLeast(0L)
        val elapsedMs = (playheadMs - startOffsetMs).coerceAtLeast(0L)
        return ActiveRecordingTimelineClip(
            trackId = recordingId,
            startOffsetMs = startOffsetMs,
            elapsedMs = elapsedMs,
        )
    }

    private fun timelineProjectionForTracks(
        tracks: List<TrackEntity>,
        waveformStates: Map<String, WaveformState>,
        playheadMs: Long = playheadPositionMs.value,
    ) =
        buildProjectTimelineProjection(
            tracks = tracks,
            waveformStatesByTrackId = waveformStates,
            activeRecording = null,
            playheadPositionMs = playheadMs,
        )

    private fun selectedPlayableTracks(): List<TrackEntity> {
        val selected = selectedTrackIds.value
        if (selected.isEmpty()) return emptyList()
        return visibleTracksWithRecordingOptimistic(
            projectTracks.value,
            optimisticTracks.value,
            recordingSession.optimisticRecordingTrack.value,
            optimisticTrackGains.value,
        )
            .filter { it.id in selected }
            .filter { it.wavFilePath.isNotBlank() }
    }

    /**
     * Wires repository/audio observation to [projectId] for this screen instance.
     *
     * Call once when [ProjectScreen] enters composition for a route argument. Switching projects should
     * navigate to a new `project/{projectId}` destination (new ViewModel), not repeatedly [bind] one VM.
     */
    suspend fun bind(projectId: String) {
        if (this.projectId.value != projectId) {
            transportController.resetPlaybackForProjectChange()
            playheadTransport.resetWhenProjectChanges()
            optimisticTracks.value = null
            optimisticTrackGains.value = emptyMap()
            waveformStatesByTrackId.value = emptyMap()
            waveformPeakPathsByTrackId.clear()
            waveformExtractionsInFlight.clear()
            recordingSession.resetWhenBoundProjectChanges()
            selectedTrackIds.value = emptySet()
        }
        this.projectId.value = projectId
    }

    fun setPlayheadPositionMs(positionMs: Long, timelineBaseDurationMs: Long) {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        if (!playheadTransport.canScrubPlayhead()) return
        playheadPositionMs.value = timelinePlayheadClampedPositionMs(positionMs, timelineBaseDurationMs)
    }

    /** Transport Seek.1: first touch on the scrubber waveform area. */
    fun onPlayheadScrubStarted() {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        if (playheadTransport.phase.value != TransportPlaybackPhase.Playing) return
        playheadTransport.beginPlaybackSeekDrag()
        transportController.pauseEnginePreservingSession()
    }

    fun onPlayheadScrubPreviewPosition(positionMs: Long, timelineDurationMs: Long) {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        val clamped = timelinePlayheadClampedPositionMs(positionMs, timelineDurationMs)
        if (playheadTransport.isPlaybackSeekDragActive()) {
            playheadTransport.setPlayheadDuringSeekDrag(clamped, timelineDurationMs)
            return
        }
        if (!playheadTransport.canScrubPlayhead()) return
        playheadPositionMs.value = clamped
    }

    fun onPlayheadScrubCommittedPosition(positionMs: Long, timelineDurationMs: Long) {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        val clamped = timelinePlayheadClampedPositionMs(positionMs, timelineDurationMs)
        if (playheadTransport.isPlaybackSeekDragActive()) {
            playheadTransport.setPlayheadDuringSeekDrag(clamped, timelineDurationMs)
            viewModelScope.launch { completePlaybackSeekDragScrub() }
            return
        }
        if (!playheadTransport.canScrubPlayhead()) return
        playheadPositionMs.value = clamped
    }

    /** Gesture ended without commit (pointer cancel / scope cancellation). */
    fun onPlayheadScrubCancelled() {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        abortPlaybackSeekDragToPaused()
    }

    internal suspend fun completePlaybackSeekDragScrub() {
        if (!playheadTransport.endPlaybackSeekDragAndConsumeResume()) return
        if (!restartPlaybackFromPlayheadAfterSeekDrag()) {
            abortPlaybackSeekDragToPaused()
        }
    }

    internal suspend fun restartPlaybackFromPlayheadAfterSeekDrag(): Boolean {
        if (!playbackSession.hasActivePlaybackSession()) return false
        val selected = selectedTrackIds.value
        if (selected.isEmpty()) return false
        val currentProjectId = projectId.value ?: return false
        val currentProject = loadCurrentProject(currentProjectId) ?: return false
        val tracks = selectedPlayableTracks()
        if (tracks.isEmpty()) return false
        val timeline = timelineProjectionForTracks(tracks, waveformStatesByTrackId.value)
        val startPositionMs =
            timelinePlayheadClampedPositionMs(playheadPositionMs.value, timeline.timelineDurationMs)
        if (timeline.timelineDurationMs > 0L && startPositionMs >= timeline.timelineDurationMs) return false
        val playbackSpec =
            currentProject.toMultiPlaybackSpec(tracks)?.copy(
                startPositionMs = startPositionMs,
                sessionTimelineEndMs = sessionTimelineEndMsForTracks(tracks),
            ) ?: return false
        if (
            !playbackSession.restartEngineFromPlayhead(
                playbackSpec,
                tracks.map { it.id },
                selected,
            )
        ) {
            return false
        }
        playheadTransport.onPlaybackStarted(fromPositionMs = startPositionMs)
        return true
    }

    private fun abortPlaybackSeekDragToPaused() {
        if (playheadTransport.isPlaybackSeekDragActive()) {
            playheadTransport.endPlaybackSeekDragAndConsumeResume()
        }
        if (playheadTransport.phase.value != TransportPlaybackPhase.Playing) return
        playheadTransport.enterPaused()
        transportController.pausePlayback()
    }

    internal fun setPlayheadNativePollEnabledForTests(enabled: Boolean) {
        playheadTransport.nativePollEnabled = enabled
    }

    internal fun advancePlayheadNativeTransportForTests(positionMs: Long) {
        playheadTransport.setNativeTransportPositionForTests(positionMs)
    }

    internal fun setSelectedTrackIdsForTests(ids: Set<String>) {
        selectedTrackIds.value = ids
    }

    internal fun sessionTrackIdsForTests(): Set<String> = playbackSession.sessionTrackIds.value

    internal fun sessionLaneTrackIdsForTests(): Array<String?> =
        playbackSession.sessionLaneTrackIdsForTests()

    internal fun cancelPlaybackCompletionMonitorForTests() {
        playbackSession.cancelCompletionMonitorForTransportStop()
    }

    /** Unit tests: simulate recording-session lock without running the full record pipeline. */
    internal fun seedRecordingSessionForTests(
        recordingId: String?,
        optimistic: TrackEntity? = null,
        startup: Boolean = false,
    ) {
        recordingSession.seedRecordingStateForTests(recordingId, optimistic, startup)
    }

    private suspend fun ensureProject(projectId: String, name: String): ProjectEntity? =
        try {
            repo.ensureProject(projectId, name)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            emitMessage(R.string.error_create_project_failed)
            null
        }

    private suspend fun loadCurrentProject(targetProjectId: String): ProjectEntity? {
        val loadedProject = repo.observeProject(targetProjectId).first()
        if (loadedProject == null) {
            emitMessage(R.string.error_project_audio_unavailable)
        }
        return loadedProject
    }

    fun renameProject(newName: String) {
        val currentProject = uiState.value.project ?: return
        val normalizedName = when (val validation = validateName(newName)) {
            is NameValidationResult.Invalid -> {
                emitMessage(validation.error.toProjectNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }
        if (normalizedName == (currentProject.name ?: "").trim()) return

        val updatedProject = currentProject.copy(name = normalizedName)
        viewModelScope.launch {
            runDbAction(R.string.error_rename_project_failed) {
                repo.upsertProject(updatedProject)
            }
        }
    }

    fun deleteTrack(trackId: String) {
        if (recordingSession.recordingTrackId.value == trackId) {
            emitMessage(R.string.error_stop_recording_to_delete_track)
            return
        }
        if (playbackSession.isTrackLoadedInSession(trackId)) {
            emitMessage(R.string.error_stop_playback_to_delete_track)
            return
        }

        val track = uiState.value.tracks.find { it.id == trackId } ?: return

        val previousSelected = selectedTrackIds.value
        selectedTrackIds.value = selectedTrackIds.value - trackId
        val remainingTracks = uiState.value.tracks
            .filter { it.id != trackId }
            .mapIndexed { i, t -> t.copy(position = i) }
        viewModelScope.launch {
            runDbActionWithRollback(
                errorResId = R.string.error_delete_track_failed,
                rollback = { selectedTrackIds.value = previousSelected }
            ) {
                repo.deleteTrack(track, remainingTracks)
            }
        }
    }

    fun renameTrack(trackId: String, newName: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val normalizedName = when (val validation = validateName(newName)) {
            is NameValidationResult.Invalid -> {
                emitMessage(validation.error.toTrackNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }
        if (normalizedName == (currentTrack.name ?: "").trim()) return

        val updatedTrack = currentTrack.copy(name = normalizedName)
        viewModelScope.launch {
            runDbAction(R.string.error_rename_track_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun toggleTrackLoop(trackId: String) {
        if (playbackSession.hasActivePlaybackSession()) return
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val updatedTrack = currentTrack.copy(isLoop = !currentTrack.isLoop)
        viewModelScope.launch {
            runDbAction(R.string.error_loop_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    /**
     * Live gain change while the user is interacting with the fader.
     * Pushes the value to the audio engine for immediate playback feedback
     * but does NOT touch the DB — that would re-emit the tracks list and
     * cause recomposition cascades that make the fader feel "jumpy".
     * The final value is persisted via [commitTrackGain] on release.
     */
    fun setTrackGain(trackId: String, gain: Float) {
        val contains = uiState.value.tracks.any { it.id == trackId }
        if (!contains) {
            return
        }
        optimisticTrackGains.value = optimisticTrackGains.value + (trackId to gain.coerceIn(GainRange.Min, GainRange.Max))
        // Live gain API applies to single-track transport-start spec, not per hot-join lane.
        if (playbackSession.sessionTrackIds.value == setOf(trackId)) {
            audioController.setPlaybackGain(GainRange.toUnit(gain))
        }
    }

    /** Commit the latest gain value to the DB. Called when the user releases the fader. */
    fun commitTrackGain(trackId: String, gain: Float) {
        val currentTrack = projectTracks.value.find { it.id == trackId }
        if (currentTrack == null) {
            return
        }
        if (gain == currentTrack.gain) {
            return
        }

        val updatedTrack = currentTrack.copy(gain = gain)
        viewModelScope.launch {
            runDbAction(R.string.error_gain_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun setTrackOrderSession(projectId: String, orderedTracks: List<TrackEntity>) {
        if (this.projectId.value != projectId) return
        if (orderedTracks.isEmpty()) return

        val next =
            OptimisticTrackOrder.applySession(
                liveTracks = uiState.value.tracks,
                proposedOrder = orderedTracks,
            )
                ?: return
        optimisticTracks.value = next
    }

    fun persistTrackOrderToDb(projectId: String) {
        if (this.projectId.value != projectId) return
        viewModelScope.launch {
            trackOrderPersistMutex.withLock {
                if (this@ProjectViewModel.projectId.value != projectId) return@withLock
                val list =
                    uiState.value.tracks.mapIndexed { index, track -> track.copy(position = index) }
                if (list.isEmpty()) return@withLock
                runDbActionWithRollback(
                    errorResId = R.string.error_save_track_order_failed,
                    rollback = { optimisticTracks.value = null }
                ) {
                    repo.updateTracks(list)
                }
            }
        }
    }

    fun toggleSelect(trackId: String) {
        val cur = selectedTrackIds.value
        if (playbackSession.wouldLeaveNoSessionLaneSelected(cur, trackId)) {
            return
        }
        val next = if (cur.contains(trackId)) cur - trackId else cur + trackId
        selectedTrackIds.value = next
        playbackSession.onSelectionChangedDuringPlayback(
            selectedTrackIds = next,
            playableTracks =
                visibleTracksWithRecordingOptimistic(
                    projectTracks.value,
                    optimisticTracks.value,
                    recordingSession.optimisticRecordingTrack.value,
                    optimisticTrackGains.value,
                ).filter { it.wavFilePath.isNotBlank() },
        )
    }

    fun importAudio(projectId: String, source: AudioImportSource, suggestedName: String?) {
        if (this.projectId.value != projectId) return
        viewModelScope.launch {
            if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) {
                emitMessage(R.string.error_stop_recording_to_import)
                return@launch
            }
            val currentProject = ensureProject(projectId, "New Project") ?: return@launch
            val visibleTrackCount = uiState.value.tracks.size

            when (
                val outcome =
                    audioImportCoordinator.run(
                        projectId = projectId,
                        project = currentProject,
                        visibleTrackCount = visibleTrackCount,
                        source = source,
                        suggestedName = suggestedName,
                    )
            ) {
                ProjectAudioImportOutcome.StorageUnavailable ->
                    emitMessage(R.string.error_import_storage_unavailable)
                is ProjectAudioImportOutcome.ImportRejected ->
                    emitMessage(outcome.failure.toUiMessage())
                is ProjectAudioImportOutcome.ReadyToPersist ->
                    runDbAction(R.string.error_save_imported_track_failed) {
                        repo.upsertTracks(listOf(outcome.importedTrack))
                    }
            }
        }
    }

    fun onRecordPressed(projectId: String, projectName: String = "New Project") {
        if (recordingSession.hasActiveRecordingTake()) {
            emitMessage(R.string.error_stop_recording_to_record)
            return
        }
        if (recordingSession.isStartupInFlight()) {
            return
        }

        val tracks =
            visibleTracksWithRecordingOptimistic(
                projectTracks.value,
                optimisticTracks.value,
                recordingSession.optimisticRecordingTrack.value,
                optimisticTrackGains.value,
            )
        val timeline = timelineProjectionForTracks(tracks, waveformStatesByTrackId.value)
        val timelineStartOffsetMs =
            timelinePlayheadClampedPositionMs(playheadPositionMs.value, timeline.timelineDurationMs)
        if (timeline.timelineDurationMs > 0L && timelineStartOffsetMs >= timeline.timelineDurationMs) {
            emitMessage(R.string.error_record_at_timeline_end)
            return
        }

        val overdubPlaybackTracks = selectedPlayableTracksForOverdub(tracks)

        recordingSession.armRecordingStartup()

        recordingSession.launchRecordPressed(
            projectId = projectId,
            projectName = projectName,
            timelineStartOffsetMs = timelineStartOffsetMs,
            ensureProject = { pid, name -> ensureProject(pid, name) },
            visibleTrackCount = { uiState.value.tracks.size },
            persistRecordingRow = { repo.upsertTracks(listOf(it)) },
            onPendingTrackAllocated = { pendingTrack ->
                val overdubStarted =
                    startOverdubPlaybackForPendingTake(
                        projectId = projectId,
                        pendingTrack = pendingTrack,
                        timelineStartOffsetMs = timelineStartOffsetMs,
                        sessionTimelineEndMs = sessionTimelineEndMsForTracks(overdubPlaybackTracks),
                        selectedPlayableTracks = overdubPlaybackTracks,
                    )
                if (!overdubStarted) {
                    abortCombinedRecordTransport()
                    emitMessage(R.string.error_playback_failed_to_start)
                }
                overdubStarted
            },
            notifyEngineStartFailed = {
                abortCombinedRecordTransport()
                emitMessage(R.string.error_recording_failed_to_start)
            },
            notifyPersistFailed = {
                abortCombinedRecordTransport()
                emitMessage(R.string.error_create_recording_track_failed)
            },
            onRecordingTransportReady = { offsetMs ->
                playheadTransport.onRecordingStarted(
                    fromPositionMs = offsetMs,
                    nativeTransportMsAtStart = audioController.transportPositionMs(),
                )
            },
        )
    }

    private fun selectedPlayableTracksForOverdub(tracks: List<TrackEntity>): List<TrackEntity> {
        val selected = selectedTrackIds.value
        if (selected.isEmpty()) return emptyList()
        return tracks
            .filter { it.id in selected }
            .filter { it.wavFilePath.isNotBlank() }
    }

    private suspend fun startOverdubPlaybackForPendingTake(
        projectId: String,
        pendingTrack: TrackEntity,
        timelineStartOffsetMs: Long,
        sessionTimelineEndMs: Long,
        selectedPlayableTracks: List<TrackEntity>,
    ): Boolean {
        if (selectedPlayableTracks.isEmpty()) {
            return true
        }
        val project = loadCurrentProject(projectId) ?: return false
        return playAndRecordTransport.startFromPlayhead(
            project = project,
            selectedPlayableTracks = selectedPlayableTracks,
            recordingTrackId = pendingTrack.id,
            startPositionMs = timelineStartOffsetMs,
            sessionTimelineEndMs = sessionTimelineEndMs,
        )
    }

    private fun abortCombinedRecordTransport() {
        playAndRecordTransport.stop()
        playheadTransport.abortRecordingStart()
    }

    fun onPlayPressed() {
        viewModelScope.launch { performPlayPressed() }
    }

    internal suspend fun performPlayPressed() {
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) {
            return
        }
        if (playheadTransport.phase.value == TransportPlaybackPhase.Recording) {
            return
        }
        if (playheadTransport.phase.value == TransportPlaybackPhase.Playing) {
            emitMessage(R.string.error_stop_playback_first)
            return
        }

        val selectedPlayableTracks = selectedPlayableTracks()
        if (selectedPlayableTracks.isEmpty()) {
            if (selectedTrackIds.value.isNotEmpty()) {
                emitMessage(R.string.error_no_audio_for_selected_tracks)
            }
            return
        }
        val currentProjectId = projectId.value ?: return
        val currentProject = loadCurrentProject(currentProjectId) ?: return
        val tracks =
            visibleTracksWithRecordingOptimistic(
                projectTracks.value,
                optimisticTracks.value,
                recordingSession.optimisticRecordingTrack.value,
                optimisticTrackGains.value,
            )
        val timeline = timelineProjectionForTracks(tracks, waveformStatesByTrackId.value)
        val startPositionMs =
            timelinePlayheadClampedPositionMs(playheadPositionMs.value, timeline.timelineDurationMs)
        if (timeline.timelineDurationMs > 0L && startPositionMs >= timeline.timelineDurationMs) return

        val playbackSpec =
            currentProject.toMultiPlaybackSpec(selectedPlayableTracks)?.copy(
                startPositionMs = startPositionMs,
                sessionTimelineEndMs = sessionTimelineEndMsForTracks(selectedPlayableTracks),
            )
        if (playbackSpec == null) {
            emitMessage(playbackStartRejectedMessage(selectedPlayableTracks.size))
            return
        }

        if (!audioController.startPlayback(playbackSpec)) {
            emitMessage(R.string.error_playback_failed_to_start)
            return
        }
        playheadTransport.onPlaybackStarted(fromPositionMs = startPositionMs)
        playbackSession.markPlayingAndStartCompletionMonitor(
            playbackSpec.lanes.map { it.trackId },
        )
    }

    /** Pause during playback; stop while paused resets playhead; recording still uses full [ProjectTransportController.stopAll]. */
    fun onStopPressed() {
        viewModelScope.launch { performStopPressed() }
    }

    internal suspend fun performStopPressed() {
        if (
            recordingSession.hasActiveRecordingTake() ||
            recordingSession.isStartupInFlight()
        ) {
            transportController.stopAll()
            playheadTransport.stopAndResetToZero()
            return
        }

        when (playheadTransport.phase.value) {
            TransportPlaybackPhase.Playing -> {
                abortPlaybackSeekDragToPaused()
            }
            TransportPlaybackPhase.Paused -> {
                transportController.pausePlayback()
                playheadTransport.stopAndResetToZero()
            }
            TransportPlaybackPhase.Idle -> Unit
            TransportPlaybackPhase.Recording -> Unit
        }
    }

    override fun onCleared() {
        transportController.stopAll()
        playheadTransport.stopAndResetToZero()
        // Release the persistent Oboe stream and streaming I/O thread once the
        // project screen goes away. Without this we'd keep the audio device
        // open for the lifetime of the process even after the user navigates
        // out, which is wasteful on battery.
        audioController.release()
        super.onCleared()
    }

    private fun finalizeRecordingTrack(trackId: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val finalizedTrack = recordingCoordinator.finalizedTrackAfterStop(currentTrack)

        viewModelScope.launch {
            runDbAction(R.string.error_recording_metadata_failed) {
                repo.upsertTrack(finalizedTrack)
            }
        }
    }

    private fun refreshWaveformPeakRequests(tracks: List<TrackEntity>) {
        val playableTracks = tracks.filter { it.wavFilePath.isNotBlank() && !it.isRecording }
        val playableIds = playableTracks.mapTo(mutableSetOf()) { it.id }
        val currentStates = waveformStatesByTrackId.value.toMutableMap()

        currentStates.keys.retainAll(playableIds)
        waveformPeakPathsByTrackId.keys.retainAll(playableIds)
        waveformExtractionsInFlight.retainAll(playableIds)

        playableTracks.forEach { track ->
            val cachedPath = waveformPeakPathsByTrackId[track.id]
            if (cachedPath != track.wavFilePath) {
                currentStates.remove(track.id)
                waveformPeakPathsByTrackId.remove(track.id)
            }
        }
        if (currentStates != waveformStatesByTrackId.value) {
            waveformStatesByTrackId.value = currentStates
        }

        playableTracks.forEach { track ->
            if (waveformPeakPathsByTrackId[track.id] == track.wavFilePath) return@forEach
            if (!waveformExtractionsInFlight.add(track.id)) return@forEach
            waveformPeakPathsByTrackId[track.id] = track.wavFilePath
            waveformStatesByTrackId.value =
                waveformStatesByTrackId.value + (track.id to WaveformState.Loading)
            viewModelScope.launch {
                val peaks = waveformPeakExtractor.extract(track.wavFilePath)
                waveformExtractionsInFlight.remove(track.id)
                if (uiState.value.tracks.any {
                        it.id == track.id &&
                            it.wavFilePath == track.wavFilePath &&
                            !it.isRecording
                    }
                ) {
                    val state =
                        if (peaks == null) {
                            WaveformState.Failed
                        } else {
                            WaveformState.Ready(peaks)
                        }
                    waveformStatesByTrackId.value = waveformStatesByTrackId.value + (track.id to state)
                }
            }
        }
    }

    @StringRes
    private fun playbackStartRejectedMessage(playableTrackCount: Int): Int =
        if (playableTrackCount == 0) {
            R.string.error_no_audio_for_selected_tracks
        } else {
            R.string.error_playback_failed_to_start
        }

}
