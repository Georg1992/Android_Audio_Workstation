package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import com.georgv.audioworkstation.core.util.logWarning
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.track.clampLoopRegionMs
import com.georgv.audioworkstation.core.track.sourceDurationMs
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.core.audio.RecordingStorageGuard
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
import com.georgv.audioworkstation.ui.components.buildProjectTimelineProjection
import com.georgv.audioworkstation.ui.components.projectTimelineClips
import com.georgv.audioworkstation.ui.components.shouldExtendVisibleTimelineForAllLoopedPlayback
import com.georgv.audioworkstation.ui.components.TimelineMaxDurationMs
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
    val timelineLaneLayoutDurationMs: Long = TimelineMinimumBaseDurationMs,
    val timelineVisibleDurationMs: Long = TimelineMinimumBaseDurationMs,
    val playheadPositionMs: Long = 0L,
    val transportPlaybackPhase: TransportPlaybackPhase = TransportPlaybackPhase.Idle,
    val isRecordingStartup: Boolean = false,
    val recordTargetTrackId: String? = null,
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
    private val audioFilePathProvider: AudioFilePathProvider,
    private val recordingStorageGuard: RecordingStorageGuard,
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)

    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val recordTargetTrackId = MutableStateFlow<String?>(null)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val waveformStatesByTrackId = MutableStateFlow<Map<String, WaveformState>>(emptyMap())
    private val playheadPositionMs = MutableStateFlow(0L)
    private val playheadTransport =
        PlayheadTransportController(
            scope = viewModelScope,
            playheadPositionMs = playheadPositionMs,
            nativeTransportPositionMs = { audioController.transportPositionMs() },
        )
    private val dbActions =
        ProjectDbActionRunner(logTag = TAG) { message -> emitMessage(message) }
    private val waveformPeaks =
        ProjectWaveformPeakCoordinator(
            scope = viewModelScope,
            waveformPeakExtractor = waveformPeakExtractor,
            waveformStatesByTrackId = waveformStatesByTrackId,
            tracksSnapshot = { uiState.value.tracks },
        )

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
    private val recordingStorageMonitor =
        RecordingStorageMonitor(
            scope = viewModelScope,
            guard = recordingStorageGuard,
        )
    private var recordingStorageMonitorEnabledForTests = true
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

    private val playheadSeek =
        ProjectPlayheadSeekCoordinator(
            scope = viewModelScope,
            playheadPositionMs = playheadPositionMs,
            playheadTransport = playheadTransport,
            playbackSession = playbackSession,
            transportController = transportController,
            recordingSession = recordingSession,
            projectId = { projectId.value },
            selectedTrackIds = { selectedTrackIds.value },
            loadCurrentProject = { pid -> loadCurrentProject(pid) },
            selectedPlayableTracks = { selectedPlayableTracks() },
            timelineTracksForPlayhead = { currentVisibleTracks() },
            timelineProjection = { tracks, waveformStates ->
                timelineProjectionForTracks(tracks, waveformStates)
            },
            waveformStatesByTrackId = { waveformStatesByTrackId.value },
        )

    private val transportCommands =
        ProjectTransportCommands(
            audioController = audioController,
            playheadPositionMs = playheadPositionMs,
            playheadTransport = playheadTransport,
            playbackSession = playbackSession,
            recordingSession = recordingSession,
            transportController = transportController,
            playheadSeek = playheadSeek,
            playAndRecordTransport = playAndRecordTransport,
            projectId = { projectId.value },
            selectedTrackIds = { selectedTrackIds.value },
            visibleTracks = { currentVisibleTracks() },
            visibleTrackCount = { uiState.value.tracks.size },
            recordTargetTrackId = { recordTargetTrackId.value },
            waveformStatesByTrackId = { waveformStatesByTrackId.value },
            timelineProjectionForTracks = { tracks, waveformStates ->
                timelineProjectionForTracks(tracks, waveformStates)
            },
            loadCurrentProject = { pid -> loadCurrentProject(pid) },
            ensureProject = { pid, name -> ensureProject(pid, name) },
            persistRecordingRow = { track -> repo.upsertTracks(listOf(track)) },
            emitMessage = { resId -> emitMessage(resId) },
            storagePrecheck = { project ->
                val directoryPath = audioFilePathProvider.projectRecordingDirectory(project.id)
                directoryPath != null && recordingStorageGuard.canStartRecording(directoryPath)
            },
            onRecordingStorageMonitorStart = { activeProjectId ->
                if (!recordingStorageMonitorEnabledForTests) return@ProjectTransportCommands
                val directoryPath = audioFilePathProvider.projectRecordingDirectory(activeProjectId)
                if (directoryPath != null) {
                    recordingStorageMonitor.start(
                        projectDirectoryPath = directoryPath,
                        isRecordingActive = { recordingSession.hasActiveRecordingTake() },
                    ) {
                        performStopRecordingForStorageExhaustion()
                    }
                }
            },
            onRecordingStorageMonitorStop = {
                recordingStorageMonitor.stop()
            },
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
                waveformPeaks.refreshPeakRequests(tracks)
            }
        }
    }

    private fun emitMessage(message: UiMessage) {
        messages.trySend(message)
    }

    private fun emitMessage(@StringRes resId: Int) {
        emitMessage(UiMessage(resId))
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
            recordTargetTrackId,
        ) { snapshot, playheadMs, transportPhase, recordTargetId ->
            val (_, screen) = snapshot
            val activeRecording =
                activeRecordingTimelineClip(
                    tracks = screen.tracks,
                    recordingTrackId = screen.recordingTrackId,
                    playheadMs = playheadMs,
                    transportPhase = transportPhase,
                )
            val extendForAllLoopedPlayback =
                shouldExtendVisibleTimelineForAllLoopedPlayback(
                    playbackSessionActive = screen.playbackSessionActive,
                    sessionTrackIds = screen.sessionTrackIds,
                    tracks = screen.tracks,
                )
            val extendForRecording = transportPhase == TransportPlaybackPhase.Recording
            val timeline =
                buildProjectTimelineProjection(
                    tracks = screen.tracks,
                    waveformStatesByTrackId = screen.waveformStatesByTrackId,
                    activeRecording = activeRecording,
                    playheadPositionMs = playheadMs,
                    extendVisibleTimelineForAllLoopedPlayback = extendForAllLoopedPlayback,
                    extendVisibleTimelineForRecording = extendForRecording,
                )
            val displayPlayheadMs =
                when {
                    transportPhase == TransportPlaybackPhase.Recording -> {
                        playheadMs.coerceAtLeast(0L)
                    }
                    extendForAllLoopedPlayback &&
                        transportPhase == TransportPlaybackPhase.Playing -> {
                        playheadMs.coerceIn(0L, TimelineMaxDurationMs)
                    }
                    else -> {
                        timelinePlayheadClampedPositionMs(playheadMs, timeline.visibleTimelineDurationMs)
                    }
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
                timelineBaseDurationMs = timeline.baseTimelineDurationMs,
                timelineLaneLayoutDurationMs = timeline.laneLayoutDurationMs,
                timelineVisibleDurationMs = timeline.visibleTimelineDurationMs,
                playheadPositionMs = displayPlayheadMs,
                transportPlaybackPhase = transportPhase,
                isRecordingStartup = screen.isRecordingStartup,
                recordTargetTrackId = recordTargetId,
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
            extendVisibleTimelineForAllLoopedPlayback = false,
            extendVisibleTimelineForRecording = false,
        )

    private fun currentVisibleTracks(): List<TrackEntity> =
        visibleTracksWithRecordingOptimistic(
            projectTracks.value,
            optimisticTracks.value,
            recordingSession.optimisticRecordingTrack.value,
            optimisticTrackGains.value,
        )

    private fun selectedPlayableTracks(): List<TrackEntity> {
        val selected = selectedTrackIds.value
        if (selected.isEmpty()) return emptyList()
        return currentVisibleTracks()
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
            playheadSeek.resetWhenProjectChanges()
            optimisticTracks.value = null
            optimisticTrackGains.value = emptyMap()
            waveformPeaks.resetWhenProjectChanges()
            recordingSession.resetWhenBoundProjectChanges()
            recordingStorageMonitor.stop()
            selectedTrackIds.value = emptySet()
            recordTargetTrackId.value = null
        }
        this.projectId.value = projectId
    }

    fun setPlayheadPositionMs(positionMs: Long, timelineBaseDurationMs: Long) {
        playheadSeek.setPlayheadPositionMs(positionMs, timelineBaseDurationMs)
    }

    fun onPlayheadScrubStarted() {
        playheadSeek.onPlayheadScrubStarted()
    }

    fun onPlayheadScrubPreviewPosition(positionMs: Long, timelineDurationMs: Long) {
        playheadSeek.onPlayheadScrubPreviewPosition(positionMs, timelineDurationMs)
    }

    fun onPlayheadScrubCommittedPosition(positionMs: Long, timelineDurationMs: Long) {
        playheadSeek.onPlayheadScrubCommittedPosition(positionMs, timelineDurationMs)
    }

    fun onPlayheadScrubCancelled() {
        playheadSeek.onPlayheadScrubCancelled()
    }

    internal suspend fun completePlaybackSeekDragScrub() {
        playheadSeek.completePlaybackSeekDragScrub()
    }

    internal suspend fun restartPlaybackFromPlayheadAfterSeekDrag(): Boolean =
        playheadSeek.restartPlaybackFromPlayheadAfterSeekDrag()

    internal fun setPlayheadNativePollEnabledForTests(enabled: Boolean) {
        playheadTransport.nativePollEnabled = enabled
    }

    internal fun setRecordingStorageMonitorEnabledForTests(enabled: Boolean) {
        recordingStorageMonitorEnabledForTests = enabled
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
            logWarning(TAG, "createProject failed", error)
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
            dbActions.run(R.string.error_rename_project_failed) {
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
        if (recordTargetTrackId.value == trackId) {
            recordTargetTrackId.value = null
        }
        val remainingTracks = uiState.value.tracks
            .filter { it.id != trackId }
            .mapIndexed { i, t -> t.copy(position = i) }
        viewModelScope.launch {
            dbActions.runWithRollback(
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
            dbActions.run(R.string.error_rename_track_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun toggleTrackLoop(trackId: String) {
        if (playbackSession.hasActivePlaybackSession()) return
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val enabling = !currentTrack.isLoop
        if (enabling && recordTargetTrackId.value == trackId) {
            recordTargetTrackId.value = null
        }
        val updatedTrack = currentTrack.copy(isLoop = enabling)
        viewModelScope.launch {
            dbActions.run(R.string.error_loop_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun updateTrackLoopRegion(trackId: String, loopStartMs: Long, loopEndMs: Long) {
        if (playbackSession.hasActivePlaybackSession()) return
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        if (!currentTrack.isLoop) return
        val sourceDuration = currentTrack.sourceDurationMs()
        if (sourceDuration <= 0L) return
        val (startMs, endMs) = clampLoopRegionMs(loopStartMs, loopEndMs, sourceDuration)
        val updatedTrack =
            currentTrack.copy(
                loopStartMs = startMs,
                loopEndMs = endMs,
            )
        viewModelScope.launch {
            dbActions.run(R.string.error_loop_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun toggleRecordTarget(trackId: String) {
        if (playbackSession.hasActivePlaybackSession()) return
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        if (uiState.value.tracks.none { it.id == trackId }) return
        val selecting = recordTargetTrackId.value != trackId
        if (selecting) {
            val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
            if (currentTrack.isLoop) {
                viewModelScope.launch {
                    dbActions.run(R.string.error_loop_update_failed) {
                        repo.upsertTrack(currentTrack.copy(isLoop = false))
                    }
                }
            }
            recordTargetTrackId.value = trackId
        } else {
            recordTargetTrackId.value = null
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
            dbActions.run(R.string.error_gain_update_failed) {
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
                dbActions.runWithRollback(
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
            playableTracks = currentVisibleTracks().filter { it.wavFilePath.isNotBlank() },
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
                    dbActions.run(R.string.error_save_imported_track_failed) {
                        repo.upsertTracks(listOf(outcome.importedTrack))
                    }
            }
        }
    }

    fun onRecordPressed(projectId: String, projectName: String = "New Project") {
        transportCommands.onRecordPressed(projectId, projectName)
    }

    fun onPlayPressed() {
        viewModelScope.launch { performPlayPressed() }
    }

    internal suspend fun performPlayPressed() {
        transportCommands.performPlayPressed()
    }

    /** Pause during playback; stop while paused resets playhead; recording still uses full [ProjectTransportController.stopAll]. */
    fun onStopPressed() {
        viewModelScope.launch { performStopPressed() }
    }

    internal suspend fun performStopPressed() {
        transportCommands.performStopPressed()
    }

    internal suspend fun performStopRecordingForStorageExhaustion() {
        if (!recordingSession.hasActiveRecordingTake()) return
        recordingStorageMonitor.stop()
        transportController.stopAll()
        playheadTransport.stopAndResetToZero()
        emitMessage(R.string.error_recording_stopped_storage)
    }

    override fun onCleared() {
        recordingStorageMonitor.stop()
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

        viewModelScope.launch {
            dbActions.run(R.string.error_recording_metadata_failed) {
                val punchContext = recordingSession.punchRecordingContext()
                try {
                    val finalizedTrack =
                        recordingCoordinator.finalizeTrackAfterStop(
                            currentTrack = currentTrack,
                            punchContext = punchContext,
                        )
                    repo.upsertTrack(finalizedTrack)
                } catch (cancel: CancellationException) {
                    recordingCoordinator.discardPunchRecordingTempFile(punchContext)
                    throw cancel
                } catch (error: Exception) {
                    recordingCoordinator.discardPunchRecordingTempFile(punchContext)
                    throw error
                } finally {
                    recordingSession.clearPunchRecordingContext()
                }
            }
        }
    }

    private companion object {
        const val TAG = "ProjectViewModel"
    }
}
