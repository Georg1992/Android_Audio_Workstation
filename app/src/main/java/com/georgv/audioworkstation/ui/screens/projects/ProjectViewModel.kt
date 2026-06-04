package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import com.georgv.audioworkstation.core.util.logWarning
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.track.clampLoopRegionMs
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.core.track.sourceDurationMs
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.core.audio.PanRange
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.core.audio.MasterPeakMeter
import com.georgv.audioworkstation.core.audio.RecordingStorageGuard
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
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
    val importProgressByTrackId: Map<String, Float> = emptyMap(),
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
    private val masterPeakHoldLinear = MutableStateFlow(0f)
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
    private val optimisticTrackPans = MutableStateFlow<Map<String, Float>>(emptyMap())
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
    private var masterPeakPollEnabledForTests = true
    /** Serializes [persistTrackOrderToDb] so overlapping drops cannot apply DB writes in the wrong order. */
    private val trackOrderPersistMutex = Mutex()
    private val importUiCoordinator = ProjectImportUiCoordinator()
    private val importJobs = mutableMapOf<String, Job>()

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
                    optimisticTrackPans.value,
                )
            },
            onPlaybackCompleted = {
                audioController.stopPlayback()
                resetMasterPeakHoldDisplay()
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
            combine(audioController.recordingInputLevel, waveformStatesByTrackId, importUiCoordinator.importUiByTrackId) {
                    level,
                    waveformStates,
                    importUi,
                ->
                Triple(level, waveformStates, importUi)
            },
        ) { pidProject, tracks, selPlay, recStartup, meterImport ->
            val (pid, proj) = pidProject
            val (selected, sessionTracks, sessionActive) = selPlay
            val (recording, startup) = recStartup
            val (recordingInputLevel, waveformStates, importUi) = meterImport
            val mergedWaveforms = importUiCoordinator.mergeWaveformStates(waveformStates, tracks)
            pid to ProjectScreenSnapshot(
                projectId = pid,
                project = proj,
                tracks = tracks,
                selectedTrackIds = selected,
                sessionTrackIds = sessionTracks,
                playbackSessionActive = sessionActive,
                recordingTrackId = recording,
                recordingInputLevel = recordingInputLevel.coerceIn(0f, 1f),
                waveformStatesByTrackId = mergedWaveforms,
                isRecordingStartup = startup,
                importProgressByTrackId = importUi.mapValues { it.value.progress },
            )
        }

    val uiState: StateFlow<ProjectUiState> =
        combine(
            projectScreenSnapshot,
            playheadPositionMs,
            playheadTransport.phase,
            recordTargetTrackId,
            masterPeakHoldLinear,
        ) { snapshot, playheadMs, transportPhase, recordTargetId, peakHoldLinear ->
            val (_, screen) = snapshot
            val activeRecording =
                activeRecordingTimelineClip(
                    tracks = screen.tracks,
                    recordingTrackId = screen.recordingTrackId,
                    playheadMs = playheadMs,
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
                    importProgressByTrackId = screen.importProgressByTrackId,
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
            val showSessionMasterPeak =
                transportPhase == TransportPlaybackPhase.Playing ||
                    transportPhase == TransportPlaybackPhase.Paused
            val masterMeter =
                MasterPeakMeter.fromPeakHoldLinear(
                    peakLinear = peakHoldLinear,
                    isStopped = !showSessionMasterPeak,
                )
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
                isImportInProgress = screen.tracks.any { it.importStatus == TrackImportStatus.IMPORTING },
                masterPeakDbText = masterMeter.peakDbText,
                masterPeakIndicatorLevel = masterMeter.indicatorLevel,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    private var masterPeakPollJob: Job? = null
    private var masterOverloadWarningShownThisSession = false

    init {
        viewModelScope.launch {
            playheadTransport.phase.collect { phase ->
                masterPeakPollJob?.cancel()
                masterPeakPollJob = null
                if (phase == TransportPlaybackPhase.Playing && masterPeakPollEnabledForTests) {
                    masterPeakPollJob =
                        viewModelScope.launch {
                            while (isActive) {
                                val nativePeak = audioController.readMasterPeakHoldLinear()
                                val updated = max(masterPeakHoldLinear.value, nativePeak)
                                if (updated != masterPeakHoldLinear.value) {
                                    masterPeakHoldLinear.value = updated
                                    maybeEmitMasterOverloadWarning(updated)
                                }
                                delay(MASTER_PEAK_HOLD_POLL_MS)
                            }
                        }
                }
            }
        }
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
            optimisticTrackPans.value,
        )

    private fun selectedPlayableTracks(): List<TrackEntity> {
        val selected = selectedTrackIds.value
        if (selected.isEmpty()) return emptyList()
        return currentVisibleTracks()
            .filter { it.id in selected }
            .filter { it.hasPersistedPlayableAudio() }
    }

    /**
     * Wires repository/audio observation to [projectId] for this screen instance.
     *
     * Call once when [ProjectScreen] enters composition for a route argument. Switching projects should
     * navigate to a new `project/{projectId}` destination (new ViewModel), not repeatedly [bind] one VM.
     */
    suspend fun bind(projectId: String) {
        val projectChanged = this.projectId.value != projectId
        if (projectChanged) {
            importJobs.values.forEach { it.cancel() }
            importJobs.clear()
            importUiCoordinator.resetWhenProjectChanges()
            transportController.resetPlaybackForProjectChange()
            resetMasterPeakHoldDisplay()
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
        this.projectId.value = projectId
        if (projectChanged) {
            recoverStaleImports(projectId)
        }
        awaitProjectTracksSynced(projectId)
    }

    /** [projectTracks] is fed by [SharingStarted.WhileSubscribed]; wait until it matches the repo. */
    private suspend fun awaitProjectTracksSynced(projectId: String) {
        val expectedTracks = repo.observeTracks(projectId).first()
        projectTracks.first { it == expectedTracks }
    }

    private suspend fun recoverStaleImports(projectId: String) {
        val stale =
            repo.observeTracks(projectId).first().filter { track ->
                track.importStatus == TrackImportStatus.IMPORTING &&
                    importJobs[track.id]?.isActive != true
            }
        if (stale.isEmpty()) return
        stale.forEach { track ->
            if (track.wavFilePath.isNotBlank()) {
                File(track.wavFilePath).delete()
            }
        }
        repo.upsertTracks(stale.map { it.copy(importStatus = TrackImportStatus.FAILED) })
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
        masterPeakPollEnabledForTests = enabled
        if (!enabled) {
            masterPeakPollJob?.cancel()
            masterPeakPollJob = null
        }
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
        if (!playbackSession.hasActivePlaybackSession() || !audioController.isPlaybackEngineRunning()) {
            return
        }
        val laneIndex = playbackSession.livePlaybackLaneIndexForTrack(trackId) ?: return
        audioController.setPlaybackLaneGain(laneIndex, GainRange.toUnit(gain))
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
        if (!playbackSession.hasActivePlaybackSession() || !audioController.isPlaybackEngineRunning()) {
            return
        }
        val laneIndex = playbackSession.livePlaybackLaneIndexForTrack(trackId) ?: return
        audioController.setPlaybackLanePan(laneIndex, clamped)
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
        viewModelScope.launch {
            bind(projectId)
            if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) {
                emitMessage(R.string.error_stop_recording_to_import)
                return@launch
            }
            val currentProject = ensureProject(projectId, "New Project") ?: return@launch
            val visibleTrackCount = uiState.value.tracks.size

            when (
                val outcome =
                    audioImportCoordinator.prepare(
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
                is ProjectAudioImportOutcome.ImportStarted -> {
                    selectedTrackIds.value = selectedTrackIds.value - outcome.importingTrack.id
                    importUiCoordinator.beginImport(outcome.importingTrack.id)
                    Mp3ImportTiming.startStage("db_upsert_importing")
                    dbActions.run(R.string.error_save_imported_track_failed) {
                        repo.upsertTracks(listOf(outcome.importingTrack))
                    }
                    Mp3ImportTiming.stopStage("db_upsert_importing")
                    launchBackgroundImport(outcome)
                }
            }
        }
    }

    private fun launchBackgroundImport(started: ProjectAudioImportOutcome.ImportStarted) {
        val trackId = started.importingTrack.id
        importJobs[trackId]?.cancel()
        importJobs[trackId] =
            viewModelScope.launch {
                try {
                    when (
                        val outcome =
                            audioImportCoordinator.executeBackgroundImport(
                                importingTrack = started.importingTrack,
                                session = started.session,
                                onProgress = { update ->
                                    importUiCoordinator.setProgress(
                                        trackId = trackId,
                                        progress = update.fraction,
                                    )
                                },
                            )
                    ) {
                        is ProjectAudioImportOutcome.ReadyToPersist -> {
                            Mp3ImportTiming.startStage("db_ready_update")
                            dbActions.run(R.string.error_save_imported_track_failed) {
                                repo.upsertTracks(listOf(outcome.importedTrack))
                            }
                            Mp3ImportTiming.stopStage("db_ready_update")
                            importUiCoordinator.clear(trackId)
                            Mp3ImportTiming.endSession("ready")
                            waveformPeaks.refreshPeakRequests(
                                currentVisibleTracks().map { track ->
                                    if (track.id == outcome.importedTrack.id) {
                                        outcome.importedTrack
                                    } else {
                                        track
                                    }
                                },
                            )
                        }
                        is ProjectAudioImportOutcome.ImportRejected -> {
                            File(started.session.destinationPath).delete()
                            dbActions.run(R.string.error_save_imported_track_failed) {
                                repo.upsertTracks(
                                    listOf(started.importingTrack.copy(importStatus = TrackImportStatus.FAILED)),
                                )
                            }
                            importUiCoordinator.clear(trackId)
                            Mp3ImportTiming.endSession("failed")
                            emitMessage(outcome.failure.toUiMessage())
                        }
                        else -> Unit
                    }
                } catch (cancel: CancellationException) {
                    File(started.session.destinationPath).delete()
                    Mp3ImportTiming.recordFailure(
                        stage = "background_import_cancelled",
                        error = cancel,
                        partialWavDeleted = true,
                    )
                    dbActions.run(R.string.error_save_imported_track_failed) {
                        repo.upsertTracks(
                            listOf(started.importingTrack.copy(importStatus = TrackImportStatus.FAILED)),
                        )
                    }
                    importUiCoordinator.clear(trackId)
                    Mp3ImportTiming.endSession("cancelled")
                    throw cancel
                } finally {
                    importJobs.remove(trackId)
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
        if (playheadTransport.phase.value == TransportPlaybackPhase.Idle) {
            resetMasterPeakHoldDisplay()
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
            resetMasterPeakHoldDisplay()
        }
    }

    private fun resetMasterPeakHoldDisplay() {
        audioController.resetMasterPeakHold()
        masterPeakHoldLinear.value = 0f
        masterOverloadWarningShownThisSession = false
    }

    fun onMasterPeakIndicatorClicked() {
        audioController.resetMasterPeakHold()
        masterPeakHoldLinear.value = 0f
    }

    private fun maybeEmitMasterOverloadWarning(peakLinear: Float) {
        if (masterOverloadWarningShownThisSession) return
        if (MasterPeakMeter.indicatorLevelForPeak(peakLinear, isStopped = false) !=
            MasterPeakIndicatorLevel.Red
        ) {
            return
        }
        masterOverloadWarningShownThisSession = true
        emitMessage(R.string.warning_master_output_overloaded)
    }

    internal suspend fun performStopRecordingForStorageExhaustion() {
        if (!recordingSession.hasActiveRecordingTake()) return
        recordingStorageMonitor.stop()
        transportController.stopAll()
        resetMasterPeakHoldDisplay()
        playheadTransport.stopAndResetToZero()
        emitMessage(R.string.error_recording_stopped_storage)
    }

    override fun onCleared() {
        recordingStorageMonitor.stop()
        transportController.stopAll()
        playheadTransport.stopAndResetToZero()
        resetMasterPeakHoldDisplay()
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
        const val MASTER_PEAK_HOLD_POLL_MS = 150L
    }
}
