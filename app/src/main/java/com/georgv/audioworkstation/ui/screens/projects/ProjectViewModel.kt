package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import com.georgv.audioworkstation.core.util.logWarning
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.track.activeMixScopeTrackIds
import com.georgv.audioworkstation.core.track.clampLoopRegionMs
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.core.track.reconcileInScopeLoopRegions
import com.georgv.audioworkstation.core.track.activeMixScopePlayableTracks
import com.georgv.audioworkstation.core.track.sourceDurationMs
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioEngineSession
import com.georgv.audioworkstation.core.audio.AudioParameterCommandQueue
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.core.audio.PanRange
import com.georgv.audioworkstation.core.audio.RecordingStorageGuard
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.AudioIoScope
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.core.audio.Mp3ImportTiming
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.audio.toUiMessage
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.toProjectNameUiMessage
import com.georgv.audioworkstation.core.validation.toTrackNameUiMessage
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.diagnostics.QuickRecordDiagnostics
import com.georgv.audioworkstation.ui.diagnostics.ThreadingDiagnostics
import com.georgv.audioworkstation.ui.diagnostics.WaveformRecompositionDiagnostics
import com.georgv.audioworkstation.ui.components.TimelineClip
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.core.audio.waveform.WavWaveformPeakExtractor
import com.georgv.audioworkstation.ui.components.TimelineMinimumBaseDurationMs
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import javax.inject.Inject

data class SampleRateMismatchDialogState(
    val sourceSampleRateHz: Int,
    val projectSampleRateHz: Int,
    val sourceSampleRateLabel: String,
    val projectSampleRateLabel: String,
    val createProjectSampleRateLabel: String,
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
    val isImportInProgress: Boolean = false,
    val masterPeakDbText: String = "0 dB",
    val masterPeakIndicatorLevel: MasterPeakIndicatorLevel = MasterPeakIndicatorLevel.Inactive,
) {
    val isPlayEnabled: Boolean
        get() =
            selectedTrackIds.any { id ->
                tracks.any { it.id == id && it.hasPersistedPlayableAudio() }
            }

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

/** High-frequency transport/meter fields — kept out of [structuralUiState] to limit recomposition. */
data class ProjectRealtimeUiState(
    /** Raw engine/global transport position (ms). */
    val playheadPositionMs: Long = 0L,
    /** Global scrubber position on the project base timeline (clamped to [timelineVisibleDurationMs]). */
    val globalPlayheadPositionMs: Long = 0L,
    val recordingInputLevel: Float = 0f,
    /** Project base timeline span; extends past base while recording or during loop playback. */
    val timelineVisibleDurationMs: Long = TimelineMinimumBaseDurationMs,
    val recordingTimelineClipsByTrackId: Map<String, TimelineClip>? = null,
    val recordingTimelineLaneLayoutDurationMs: Long? = null,
    val masterPeakDbText: String = "0 dB",
    val masterPeakIndicatorLevel: MasterPeakIndicatorLevel = MasterPeakIndicatorLevel.Inactive,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val audioController: AudioController,
    private val audioImportCoordinator: ProjectAudioImportCoordinator,
    private val pendingCompressedImportRegistry: PendingCompressedImportRegistry,
    private val recordingCoordinator: ProjectRecordingCoordinator,
    private val waveformPeakExtractor: WavWaveformPeakExtractor,
    private val audioFilePathProvider: AudioFilePathProvider,
    private val recordingStorageGuard: RecordingStorageGuard,
    private val dispatchers: AppDispatchers,
    private val audioIoScope: AudioIoScope,
    private val audioEngineSession: AudioEngineSession,
    private val audioParameterQueue: AudioParameterCommandQueue,
) : ViewModel() {

    private var engineSessionAcquired = false

    internal val appDispatchers: AppDispatchers
        get() = dispatchers

    internal val importRepo: ProjectRepository
        get() = repo
    internal val importCoordinator: ProjectAudioImportCoordinator
        get() = audioImportCoordinator
    internal val pendingImportRegistry: PendingCompressedImportRegistry
        get() = pendingCompressedImportRegistry

    private val projectId = MutableStateFlow<String?>(null)

    internal val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    internal val recordTargetTrackId = MutableStateFlow<String?>(null)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    internal val importMessageChannel: Channel<UiMessage>
        get() = messages
    internal val openProjectRequestEvents = Channel<String>(capacity = Channel.BUFFERED)
    internal val sampleRateMismatchDialog = MutableStateFlow<SampleRateMismatchDialogState?>(null)
    internal var pendingCompressedImport: PendingCompressedImport? = null
    private val waveformStatesByTrackId = MutableStateFlow<Map<String, WaveformState>>(emptyMap())
    private val playheadPositionMs = MutableStateFlow(0L)
    private val playheadTransport =
        PlayheadTransportController(
            scope = viewModelScope,
            playheadPositionMs = playheadPositionMs,
            nativeTransportPositionMs = { audioController.transportPositionMs() },
            pollDispatcher = dispatchers.default,
        )
    private val masterPeakController =
        MasterPeakController(
            scope = viewModelScope,
            audioController = audioController,
            dispatchers = dispatchers,
            transportPhase = playheadTransport.phase,
            onOverloadWarning = { emitMessage(R.string.warning_master_output_overloaded) },
        )
    private val dbActions =
        ProjectDbActionRunner(logTag = TAG) { message -> emitMessage(message) }
    internal val importDbActions: ProjectDbActionRunner
        get() = dbActions
    internal val waveformPeaks =
        ProjectWaveformPeakCoordinator(
            scope = viewModelScope,
            waveformPeakExtractor = waveformPeakExtractor,
            waveformStatesByTrackId = waveformStatesByTrackId,
            tracksSnapshot = { currentVisibleTracks() },
            ioDispatcher = waveformPeakExtractor.ioDispatcher,
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
    private val optimisticTrackPans = MutableStateFlow<Map<String, Float>>(emptyMap())
    internal val recordingSession =
        RecordingSessionController(
            scope = viewModelScope,
            audioController = audioController,
            recordingCoordinator = recordingCoordinator,
            dispatchers = dispatchers,
        )
    private val recordingStorageMonitor =
        RecordingStorageMonitor(
            scope = viewModelScope,
            guard = recordingStorageGuard,
            dispatchers = dispatchers,
        )
    private var recordingStorageMonitorEnabledForTests = true
    /** Serializes [persistTrackOrderToDb] so overlapping drops cannot apply DB writes in the wrong order. */
    private val trackOrderPersistMutex = Mutex()
    internal val importUiCoordinator = ProjectImportUiCoordinator()
    internal val importSession = ProjectImportSession()

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
            dispatchers = dispatchers,
            loadCurrentProject = { pid -> loadCurrentProject(pid) },
            currentProjectId = { projectId.value },
            visibleTracks = {
                visibleTracksWithRecordingOptimistic(
                    projectTracks.value,
                    optimisticTracks.value,
                    recordingSession.optimisticRecordingTrack.value,
                    optimisticTrackGains.value,
                    optimisticTrackPans.value,
                )
            },
            onPlaybackCompleted = {
                masterPeakController.resetDisplayAndNativeHold()
                playheadTransport.stopAndResetToZero()
                viewModelScope.launch(dispatchers.audioIo) {
                    withAudioIo(dispatchers, "AudioController.stopPlayback completion") {
                        audioController.stopPlayback()
                    }
                }
            },
            suppressTransportOnPlaybackCompletion = { recordingSession.hasActiveRecordingTake() },
            onHotJoinFailed = { viewModelScope.launch { emitMessage(R.string.error_playback_failed_to_start) } },
        )

    private val playAndRecordTransport =
        PlayAndRecordTransport(
            audioController = audioController,
            playbackSession = playbackSession,
            dispatchers = dispatchers,
        )

    private val transportController = ProjectTransportController(
        audioController = audioController,
        playbackSession = playbackSession,
        recordingSession = recordingSession,
        dispatchers = dispatchers,
        finalizeRecordingTrackAfterSuccessfulEngineStop = { trackId, firstSampleTransportPositionMs ->
            finalizeRecordingTrack(trackId, firstSampleTransportPositionMs)
        },
    )

    private val scopePlaybackCoordinator =
        ScopePlaybackCoordinator(
            audioController = audioController,
            dispatchers = dispatchers,
            playheadPositionMs = playheadPositionMs,
            playheadTransport = playheadTransport,
            playbackSession = playbackSession,
            transportController = transportController,
            recordingSession = recordingSession,
            playAndRecordTransport = playAndRecordTransport,
            projectId = { projectId.value },
            visibleTracks = { currentVisibleTracks() },
            loadCurrentProject = { pid -> loadCurrentProject(pid) },
            timelineVisibleDurationMs = { realtimeUiState.value.timelineVisibleDurationMs },
            timelineBaseDurationMs = { structuralUiState.value.timelineBaseDurationMs },
        )

    private val playheadSeek =
        ProjectPlayheadSeekCoordinator(
            scope = viewModelScope,
            playheadPositionMs = playheadPositionMs,
            playheadTransport = playheadTransport,
            playbackSession = playbackSession,
            transportController = transportController,
            recordingSession = recordingSession,
            scopePlaybackCoordinator = scopePlaybackCoordinator,
            projectId = { projectId.value },
            selectedTrackIds = { selectedTrackIds.value },
        )

    private val transportCommands =
        ProjectTransportCommands(
            audioController = audioController,
            dispatchers = dispatchers,
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
            visibleTrackCount = { structuralUiState.value.tracks.size },
            recordTargetTrackId = { recordTargetTrackId.value },
            timelineVisibleDurationMs = { realtimeUiState.value.timelineVisibleDurationMs },
            timelineBaseDurationMs = { structuralUiState.value.timelineBaseDurationMs },
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
            isImportInProgress = { structuralUiState.value.isImportInProgress },
        )

    val userMessages = messages.receiveAsFlow()
    val openProjectRequests = openProjectRequestEvents.receiveAsFlow()

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
            combine(projectTracks, optimisticTrackPans) { tracks, pans -> tracks to pans }
                .collect { (tracks, pans) ->
                    if (pans.isEmpty()) return@collect
                    val next =
                        pans.filter { (trackId, pan) ->
                            tracks.any { it.id == trackId && it.pan != pan }
                        }
                    if (next.size != pans.size) {
                        optimisticTrackPans.value = next
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
                    optimisticTrackPans.value,
                )
            }.collect { tracks ->
                if (waveformPeakRefreshEnabled) {
                    waveformPeaks.refreshPeakRequests(tracks)
                }
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
            combine(projectTracks, optimisticTracks, recordingSession.optimisticRecordingTrack, optimisticTrackGains, optimisticTrackPans) {
                    projectTracksList,
                    optimisticOrder,
                    optimisticRecording,
                    optimisticGains,
                    optimisticPans,
                ->
                visibleTracksWithRecordingOptimistic(
                    projectTracksList,
                    optimisticOrder,
                    optimisticRecording,
                    optimisticGains,
                    optimisticPans,
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
            combine(waveformStatesByTrackId, importUiCoordinator.importUiByTrackId) { waveformStates, importUi ->
                waveformStates to importUi
            },
        ) { pidProject, tracks, selPlay, recStartup, waveformImport ->
            val (pid, proj) = pidProject
            val (selected, sessionTracks, sessionActive) = selPlay
            val (recording, startup) = recStartup
            val (waveformStates, importUi) = waveformImport
            val mergedWaveforms = importUiCoordinator.mergeWaveformStates(waveformStates, tracks)
            WaveformRecompositionDiagnostics.logProjectScreenSnapshotEmission(
                waveformStates = mergedWaveforms,
                recordingInputLevel = 0f,
                importUiSize = importUi.size,
            )
            pid to ProjectScreenSnapshot(
                projectId = pid,
                project = proj,
                tracks = tracks,
                selectedTrackIds = selected,
                sessionTrackIds = sessionTracks,
                playbackSessionActive = sessionActive,
                recordingTrackId = recording,
                waveformStatesByTrackId = mergedWaveforms,
                isRecordingStartup = startup,
                importProgressByTrackId = importUi.mapValues { it.value.progress },
            )
        }

    val structuralUiState: StateFlow<ProjectUiState> =
        combine(
            projectScreenSnapshot,
            playheadTransport.phase,
            recordTargetTrackId,
        ) { snapshot, transportPhase, recordTargetId ->
            val (_, screen) = snapshot
            val timeline = buildStructuralTimelineProjection(screen)
            ProjectUiState(
                projectId = screen.projectId,
                project = screen.project,
                tracks = screen.tracks,
                selectedTrackIds = screen.selectedTrackIds,
                sessionTrackIds = screen.sessionTrackIds,
                playbackSessionActive = screen.playbackSessionActive,
                recordingTrackId = screen.recordingTrackId,
                waveformStatesByTrackId = screen.waveformStatesByTrackId,
                timelineClipsByTrackId = timeline.clipsByLaneId,
                timelineBaseDurationMs = timeline.baseTimelineDurationMs,
                timelineLaneLayoutDurationMs = timeline.laneLayoutDurationMs,
                timelineVisibleDurationMs = timeline.visibleTimelineDurationMs,
                transportPlaybackPhase = transportPhase,
                isRecordingStartup = screen.isRecordingStartup,
                recordTargetTrackId = recordTargetId,
                isImportInProgress = screen.tracks.any { it.importStatus == TrackImportStatus.IMPORTING },
            )
        }
            .distinctUntilChanged()
            .flowOn(dispatchers.default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    val realtimeUiState: StateFlow<ProjectRealtimeUiState> =
        combine(
            playheadPositionMs,
            audioController.recordingInputLevel,
            masterPeakController.peakHoldLinear,
            structuralUiState,
            playheadTransport.phase,
        ) { playheadMs, recordingLevel, peakHoldLinear, structural, transportPhase ->
            buildProjectRealtimeUiState(
                playheadMs = playheadMs,
                recordingLevel = recordingLevel,
                peakHoldLinear = peakHoldLinear,
                structural = structural,
                transportPhase = transportPhase,
            )
        }
            .flowOn(dispatchers.default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectRealtimeUiState())

    val uiState: StateFlow<ProjectUiState> =
        combine(structuralUiState, realtimeUiState) { structural, realtime ->
            structural.mergeRealtime(realtime)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    val sampleRateMismatchDialogState = sampleRateMismatchDialog.asStateFlow()

    private val _destinationReady = MutableStateFlow(false)
    /** True after [scheduleBind] completes for the current route; gates heavy workspace reveal. */
    val destinationReady: StateFlow<Boolean> = _destinationReady.asStateFlow()
    private var waveformPeakRefreshEnabled = false

    init {
        viewModelScope.launch {
            structuralUiState
                .map { it.timelineBaseDurationMs to it.transportPlaybackPhase }
                .distinctUntilChanged()
                .collect { (baseDurationMs, _) ->
                    playheadTransport.setTimelineBaseDurationMs(baseDurationMs)
                }
        }
        if (WaveformRecompositionDiagnostics.loggingEnabled) {
            viewModelScope.launch {
                var previousStructural: ProjectUiState? = null
                structuralUiState.collect { next ->
                    WaveformRecompositionDiagnostics.logStructuralUiStateEmission(previousStructural, next)
                    previousStructural = next
                }
            }
            viewModelScope.launch {
                var previousCombined: ProjectUiState? = null
                uiState.collect { next ->
                    WaveformRecompositionDiagnostics.logUiStateEmission(previousCombined, next)
                    previousCombined = next
                }
            }
        }
    }

    internal fun currentVisibleTracks(): List<TrackEntity> =
        visibleTracksWithRecordingOptimistic(
            projectTracks.value,
            optimisticTracks.value,
            recordingSession.optimisticRecordingTrack.value,
            optimisticTrackGains.value,
            optimisticTrackPans.value,
        )

    private fun selectedPlayableTracks(): List<TrackEntity> =
        activeMixScopePlayableTracks(currentVisibleTracks(), selectedTrackIds.value)

    /**
     * Wires repository/audio observation to [projectId] for this screen instance.
     *
     * Call once when [ProjectScreen] enters composition for a route argument. Switching projects should
     * navigate to a new `project/{projectId}` destination (new ViewModel), not repeatedly [bind] one VM.
     */
    suspend fun bind(projectId: String) {
        ensureEngineSessionAcquired()
        val projectChanged = this.projectId.value != projectId
        if (projectChanged) {
            WaveformRecompositionDiagnostics.resetSession()
            audioParameterQueue.clearPending()
            importSession.cancelAllJobsAndClear()
            importUiCoordinator.resetWhenProjectChanges()
            transportController.resetPlaybackForProjectChange()
            masterPeakController.resetDisplayAndNativeHold()
            playheadSeek.resetWhenProjectChanges()
            optimisticTracks.value = null
            optimisticTrackGains.value = emptyMap()
            optimisticTrackPans.value = emptyMap()
            waveformPeaks.resetWhenProjectChanges()
            recordingSession.resetWhenBoundProjectChanges()
            recordingStorageMonitor.stop()
            selectedTrackIds.value = emptySet()
            recordTargetTrackId.value = null
        }
        clearSampleRateMismatchPromptState()
        this.projectId.value = projectId
        if (projectChanged) {
            withIo(dispatchers, "recoverStaleImports") {
                recoverStaleImports(repo, projectId, importSession.jobs)
            }
        }
        withIo(dispatchers, "bind awaitProjectTracksSynced") {
            awaitProjectTracksSynced(repo, projectTracks, projectId)
        }
        tryStartRegistryPendingCompressedImport(projectId)
        waveformPeakRefreshEnabled = true
        waveformPeaks.refreshPeakRequests(currentVisibleTracks())
    }

    /** Starts [bind] off the composition critical path; exposes [destinationReady] when complete. */
    fun scheduleBind(projectId: String) {
        viewModelScope.launch {
            _destinationReady.value = false
            waveformPeakRefreshEnabled = false
            val bindStartMs = android.os.SystemClock.uptimeMillis()
            if (QuickRecordDiagnostics.isActiveFor(projectId)) {
                QuickRecordDiagnostics.logStepStart("ProjectViewModel scheduleBind", projectId)
            }
            bind(projectId)
            if (ProjectDiagnostics.loggingEnabled) {
                ProjectDiagnostics.logBindFinished(
                    projectId,
                    android.os.SystemClock.uptimeMillis() - bindStartMs,
                )
            }
            if (QuickRecordDiagnostics.isActiveFor(projectId)) {
                QuickRecordDiagnostics.logStepEnd(
                    "ProjectViewModel scheduleBind",
                    bindStartMs,
                    projectId,
                )
            }
            _destinationReady.value = true
        }
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

    internal fun setMasterPeakPollEnabledForTests(enabled: Boolean) {
        masterPeakController.setPollEnabledForTests(enabled)
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
        val pid = projectId.value ?: return
        val normalizedName = when (val validation = validateName(newName)) {
            is NameValidationResult.Invalid -> {
                emitMessage(validation.error.toProjectNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }
        viewModelScope.launch {
            dbActions.run(R.string.error_rename_project_failed) {
                val base =
                    uiState.value.project?.takeIf { it.id == pid }
                        ?: repo.observeProject(pid).first()
                        ?: ProjectEntity(id = pid)
                if (normalizedName == (base.name ?: "").trim()) return@run
                repo.upsertProject(base.copy(name = normalizedName))
            }
        }
    }

    fun cancelImport(trackId: String) {
        val track = uiState.value.tracks.find { it.id == trackId } ?: return
        if (track.importStatus != TrackImportStatus.IMPORTING) return

        importSession.userCancelledTrackIds.add(trackId)
        importUiCoordinator.clear(trackId)

        val previousSelected = selectedTrackIds.value
        importSession.cancelSelectionRollback[trackId] = previousSelected
        selectedTrackIds.value = selectedTrackIds.value - trackId
        if (recordTargetTrackId.value == trackId) {
            recordTargetTrackId.value = null
        }

        val job = importSession.jobs[trackId]
        if (job?.isActive == true) {
            job.cancel()
            return
        }

        importSession.userCancelledTrackIds.remove(trackId)
        viewModelScope.launch {
            removeCancelledImportTrack(
                trackId = trackId,
                track = track,
                destinationPath = track.wavFilePath,
            )
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
        if (track.importStatus == TrackImportStatus.IMPORTING) return

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
        if (uiState.value.isImportInProgress) return
        if (uiState.value.tracks.none { it.id == trackId }) return
        val selecting = recordTargetTrackId.value != trackId
        if (selecting) {
            val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
            if (currentTrack.importStatus != TrackImportStatus.READY) return
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
        if (!playbackSession.hasActivePlaybackSession()) {
            return
        }
        val laneIndex = playbackSession.livePlaybackLaneIndexForTrack(trackId) ?: return
        ThreadingDiagnostics.logLiveParameterEnqueue("setTrackGain lane=$laneIndex")
        audioParameterQueue.setLaneGain(laneIndex, GainRange.toUnit(gain))
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

    /**
     * Live pan while the knob is dragged: pushes to the audio engine only.
     * UI state stays in [TrackPanKnob] until [commitTrackPan] on release.
     */
    fun setTrackPan(trackId: String, pan: Float) {
        if (!uiState.value.tracks.any { it.id == trackId }) {
            return
        }
        val clamped = PanRange.clamp(pan)
        if (!playbackSession.hasActivePlaybackSession()) {
            return
        }
        val laneIndex = playbackSession.livePlaybackLaneIndexForTrack(trackId) ?: return
        ThreadingDiagnostics.logLiveParameterEnqueue("setTrackPan lane=$laneIndex")
        audioParameterQueue.setLanePan(laneIndex, clamped)
    }

    fun commitTrackPan(trackId: String, pan: Float) {
        val currentTrack = projectTracks.value.find { it.id == trackId } ?: return
        val clamped = PanRange.clamp(pan)
        if (clamped == currentTrack.pan) {
            optimisticTrackPans.value = optimisticTrackPans.value - trackId
            return
        }
        optimisticTrackPans.value = optimisticTrackPans.value + (trackId to clamped)
        val updatedTrack = currentTrack.copy(pan = clamped)
        viewModelScope.launch {
            dbActions.run(R.string.error_pan_update_failed) {
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
        val track = currentVisibleTracks().find { it.id == trackId } ?: return
        if (track.importStatus != TrackImportStatus.READY) {
            return
        }
        if (
            recordingSession.hasActiveRecordingTake() &&
            trackId == recordingSession.recordingTrackId.value
        ) {
            return
        }
        val cur = selectedTrackIds.value
        val next = if (cur.contains(trackId)) cur - trackId else cur + trackId
        selectedTrackIds.value = next
        persistReconciledLoopRegionsForScope(next)
        if (playbackSession.hasActivePlaybackSession()) {
            viewModelScope.launch {
                scopePlaybackCoordinator.onSelectionChangedDuringTransport(next)
            }
        }
    }

    private fun persistReconciledLoopRegionsForScope(selectedTrackIds: Set<String>) {
        val scopeTrackIds =
            activeMixScopeTrackIds(
                selectedTrackIds = selectedTrackIds,
                activeRecordingTrackId = recordingSession.recordingTrackId.value,
            )
        val reconciled = reconcileInScopeLoopRegions(currentVisibleTracks(), scopeTrackIds)
        val updates =
            reconciled.filter { updated ->
                val current = currentVisibleTracks().find { it.id == updated.id } ?: return@filter false
                updated.isLoop != current.isLoop ||
                    updated.loopStartMs != current.loopStartMs ||
                    updated.loopEndMs != current.loopEndMs
            }
        if (updates.isEmpty()) return
        viewModelScope.launch {
            dbActions.run(R.string.error_loop_update_failed) {
                repo.updateTracks(updates)
            }
        }
    }

    fun importAudio(projectId: String, source: AudioImportSource, suggestedName: String?) {
        viewModelScope.launch {
            bind(projectId)
            if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) {
                emitMessage(R.string.error_stop_recording_to_import)
                return@launch
            }
            val currentProject = ensureProject(projectId, "New Project") ?: return@launch
            handleImportPrepareOutcome(
                audioImportCoordinator.prepare(
                    projectId = projectId,
                    project = currentProject,
                    visibleTrackCount = uiState.value.tracks.size,
                    source = source,
                    suggestedName = suggestedName,
                ),
            )
        }
    }

    fun onRecordPressed(projectId: String, projectName: String = "New Project") {
        transportCommands.onRecordPressed(projectId, projectName)
    }

    fun onPlayPressed() {
        viewModelScope.launch { performPlayPressed() }
    }

    internal suspend fun performPlayPressed() {
        if (playheadTransport.phase.value == TransportPlaybackPhase.Idle) {
            masterPeakController.resetDisplayAndNativeHold()
        }
        transportCommands.performPlayPressed()
    }

    /** Pause during playback; stop while paused resets playhead; recording still uses full [ProjectTransportController.stopAll]. */
    fun onStopPressed() {
        viewModelScope.launch { performStopPressed() }
    }

    internal suspend fun performStopPressed() {
        transportCommands.performStopPressed()
        if (playheadTransport.phase.value == TransportPlaybackPhase.Idle) {
            masterPeakController.resetDisplayAndNativeHold()
        }
    }

    fun onMasterPeakIndicatorClicked() {
        masterPeakController.onIndicatorClicked()
    }

    internal suspend fun performStopRecordingForStorageExhaustion() {
        if (!recordingSession.hasActiveRecordingTake()) return
        recordingStorageMonitor.stop()
        transportController.stopAll()
        masterPeakController.resetDisplayAndNativeHold()
        playheadTransport.stopAndResetToZero()
        emitMessage(R.string.error_recording_stopped_storage)
    }

    override fun onCleared() {
        recordingStorageMonitor.stop()
        if (!engineSessionAcquired) {
            super.onCleared()
            return
        }
        engineSessionAcquired = false
        audioIoScope.scope.launch {
            transportController.stopAll()
            audioEngineSession.release {
                withAudioIo(dispatchers, "AudioController.release") {
                    audioController.release()
                }
            }
            withContext(dispatchers.main) {
                playheadTransport.stopAndResetToZero()
                masterPeakController.clearDisplayOnTeardown()
            }
        }
        super.onCleared()
    }

    private suspend fun ensureEngineSessionAcquired() {
        if (engineSessionAcquired) return
        audioEngineSession.acquire()
        engineSessionAcquired = true
    }

    private fun finalizeRecordingTrack(trackId: String, firstSampleTransportPositionMs: Long) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return

        viewModelScope.launch {
            dbActions.run(R.string.error_recording_metadata_failed) {
                val punchContext = recordingSession.punchRecordingContext()
                try {
                    val finalizedTrack =
                        withIo(dispatchers, "WavPunchSplicer.splice") {
                            recordingCoordinator.finalizeTrackAfterStop(
                                currentTrack = currentTrack,
                                punchContext = punchContext,
                                firstSampleTransportPositionMs = firstSampleTransportPositionMs,
                            )
                        }
                    repo.upsertTrack(finalizedTrack)
                } catch (cancel: CancellationException) {
                    withIo(dispatchers, "discard punch temp file") {
                        recordingCoordinator.discardPunchRecordingTempFile(punchContext)
                    }
                    throw cancel
                } catch (error: Exception) {
                    withIo(dispatchers, "discard punch temp file") {
                        recordingCoordinator.discardPunchRecordingTempFile(punchContext)
                    }
                    throw error
                } finally {
                    recordingSession.clearPunchRecordingContext()
                }
            }
        }
    }

    internal companion object {
        const val TAG = "ProjectViewModel"
    }
}
