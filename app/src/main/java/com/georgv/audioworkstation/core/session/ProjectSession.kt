package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioEngineSession
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.CapturePort
import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.core.audio.RecordingStopSnapshot
import com.georgv.audioworkstation.core.audio.RecordingStorageGuard
import com.georgv.audioworkstation.core.audio.capability.LiveOverdubLatencySessionRecorder
import com.georgv.audioworkstation.core.audio.capability.LiveSessionProfiling
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.AudioIoScope
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns the project-screen audio session: engine acquire/release, playhead, playback, recording,
 * and transport coordinators. [com.georgv.audioworkstation.ui.screens.projects.ProjectViewModel]
 * binds a project id and maps this graph plus repository flows into UI state.
 */
class ProjectSession(
    private val scope: CoroutineScope,
    private val playback: PlaybackPort,
    private val capture: CapturePort,
    private val meter: MeterPort,
    private val recordingCoordinator: ProjectRecordingCoordinator,
    private val dispatchers: AppDispatchers,
    private val audioIoScope: AudioIoScope,
    private val audioEngineSession: AudioEngineSession,
    private val sessionTransportGate: SessionTransportCapabilityGate,
    private val recordingSessionLatencyAudit: LiveOverdubLatencySessionRecorder,
    private val audioFilePathProvider: AudioFilePathProvider,
    private val recordingStorageGuard: RecordingStorageGuard,
    private val projectId: () -> String?,
    private val selectedTrackIds: () -> Set<String>,
    private val recordTargetTrackId: () -> String?,
    private val visibleTracks: () -> List<TrackEntity>,
    private val visibleTrackCount: () -> Int,
    private val timelineVisibleDurationMs: () -> Long,
    private val timelineBaseDurationMs: () -> Long,
    private val loadCurrentProject: suspend (String) -> ProjectEntity?,
    private val ensureProject: suspend (String, String) -> ProjectEntity?,
    private val persistRecordingRow: suspend (TrackEntity) -> Unit,
    private val emitMessage: (Int) -> Unit,
    private val isImportInProgress: () -> Boolean,
    private val boundProjectSampleRateHz: () -> Int,
    private val finalizeRecordingTrackAfterSuccessfulEngineStop: (String, RecordingStopSnapshot) -> Unit,
) {
    val playheadPositionMs = MutableStateFlow(0L)

    val playheadTransport =
        PlayheadTransportController(
            scope = scope,
            playheadPositionMs = playheadPositionMs,
            nativeTransportPositionMs = { meter.transportPositionMs() },
            pollDispatcher = dispatchers.default,
            sessionOutputLatencyMs = {
                PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(meter)
            },
        )

    val masterPeakController =
        MasterPeakController(
            scope = scope,
            meter = meter,
            dispatchers = dispatchers,
            transportPhase = playheadTransport.phase,
            onOverloadWarning = { emitMessage(R.string.warning_master_output_overloaded) },
        )

    val recordingSession =
        RecordingSessionController(
            scope = scope,
            capture = capture,
            meter = meter,
            recordingCoordinator = recordingCoordinator,
            dispatchers = dispatchers,
            sessionTransportGate = sessionTransportGate,
        )

    private val recordingStorageMonitor =
        RecordingStorageMonitor(
            scope = scope,
            guard = recordingStorageGuard,
            dispatchers = dispatchers,
        )

    private var recordingStorageMonitorEnabledForTests = true
    private var engineSessionAcquired = false

    val playbackSession =
        PlaybackSessionController(
            scope = scope,
            playback = playback,
            dispatchers = dispatchers,
            loadCurrentProject = loadCurrentProject,
            currentProjectId = projectId,
            visibleTracks = visibleTracks,
            onPlaybackCompleted = {
                masterPeakController.resetDisplayAndNativeHold()
                playheadTransport.stopAndResetToZero()
                scope.launch(dispatchers.audioIo) {
                    withAudioIo(dispatchers, "PlaybackPort.stopPlayback completion") {
                        playback.stopPlayback()
                    }
                }
            },
            suppressTransportOnPlaybackCompletion = { recordingSession.hasActiveRecordingTake() },
        )

    private val playAndRecordTransport =
        PlayAndRecordTransport(
            playback = playback,
            playbackSession = playbackSession,
            dispatchers = dispatchers,
        )

    val transportController =
        ProjectTransportController(
            capture = capture,
            playbackSession = playbackSession,
            recordingSession = recordingSession,
            dispatchers = dispatchers,
            finalizeRecordingTrackAfterSuccessfulEngineStop = finalizeRecordingTrackAfterSuccessfulEngineStop,
            onLiveOverdubSessionEnd = { snapshot ->
                if (!LiveSessionProfiling.captureOnOverdubEnd) return@ProjectTransportController
                scope.launch {
                    val sampleRateHz = boundProjectSampleRateHz()
                    withAudioIo(dispatchers, "RecordingSessionLatencyAudit.recordLiveOverdubSessionEnd") {
                        recordingSessionLatencyAudit.recordLiveOverdubSessionEnd(
                            sampleRateHz = sampleRateHz,
                            capture = snapshot,
                        )
                    }
                }
            },
        )

    internal val scopePlaybackCoordinator =
        ScopePlaybackCoordinator(
            meter = meter,
            dispatchers = dispatchers,
            sessionTransportGate = sessionTransportGate,
            playheadPositionMs = playheadPositionMs,
            playheadTransport = playheadTransport,
            playbackSession = playbackSession,
            transportController = transportController,
            recordingSession = recordingSession,
            playAndRecordTransport = playAndRecordTransport,
            projectId = projectId,
            visibleTracks = visibleTracks,
            loadCurrentProject = loadCurrentProject,
            timelineVisibleDurationMs = timelineVisibleDurationMs,
            timelineBaseDurationMs = timelineBaseDurationMs,
        )

    internal val playheadSeek =
        ProjectPlayheadSeekCoordinator(
            scope = scope,
            playheadPositionMs = playheadPositionMs,
            playheadTransport = playheadTransport,
            playbackSession = playbackSession,
            transportController = transportController,
            recordingSession = recordingSession,
            scopePlaybackCoordinator = scopePlaybackCoordinator,
            projectId = projectId,
            selectedTrackIds = selectedTrackIds,
        )

    private val transportCommands =
        ProjectTransportCommands(
            playback = playback,
            meter = meter,
            dispatchers = dispatchers,
            sessionTransportGate = sessionTransportGate,
            playheadPositionMs = playheadPositionMs,
            playheadTransport = playheadTransport,
            playbackSession = playbackSession,
            recordingSession = recordingSession,
            transportController = transportController,
            playheadSeek = playheadSeek,
            playAndRecordTransport = playAndRecordTransport,
            projectId = projectId,
            selectedTrackIds = selectedTrackIds,
            visibleTracks = visibleTracks,
            visibleTrackCount = visibleTrackCount,
            recordTargetTrackId = recordTargetTrackId,
            timelineVisibleDurationMs = timelineVisibleDurationMs,
            timelineBaseDurationMs = timelineBaseDurationMs,
            loadCurrentProject = loadCurrentProject,
            ensureProject = ensureProject,
            persistRecordingRow = persistRecordingRow,
            emitMessage = emitMessage,
            storagePrecheck = { project ->
                val directoryPath = audioFilePathProvider.projectRecordingDirectory(project.id)
                directoryPath != null && recordingStorageGuard.canStartRecording(directoryPath)
            },
            onRecordingStorageMonitorStart = { activeProjectId ->
                startRecordingStorageMonitor(activeProjectId)
            },
            onRecordingStorageMonitorStop = {
                recordingStorageMonitor.stop()
            },
            isImportInProgress = isImportInProgress,
        )

    suspend fun ensureEngineSessionAcquired() {
        if (engineSessionAcquired) return
        audioEngineSession.acquire()
        engineSessionAcquired = true
    }

    fun resetWhenBoundProjectChanges() {
        transportController.resetPlaybackForProjectChange()
        masterPeakController.resetDisplayAndNativeHold()
        playheadSeek.resetWhenProjectChanges()
        recordingSession.resetWhenBoundProjectChanges()
        recordingStorageMonitor.stop()
    }

    fun onRecordPressed(projectId: String, projectName: String) {
        transportCommands.onRecordPressed(projectId, projectName)
    }

    suspend fun performPlayPressed() {
        if (playheadTransport.phase.value == TransportPlaybackPhase.Idle) {
            masterPeakController.resetDisplayAndNativeHold()
        }
        transportCommands.performPlayPressed()
    }

    suspend fun performStopPressed() {
        transportCommands.performStopPressed()
        if (playheadTransport.phase.value == TransportPlaybackPhase.Idle) {
            masterPeakController.resetDisplayAndNativeHold()
        }
    }

    fun onMasterPeakIndicatorClicked() {
        masterPeakController.onIndicatorClicked()
    }

    suspend fun performStopRecordingForStorageExhaustion() {
        if (!recordingSession.hasActiveRecordingTake()) return
        recordingStorageMonitor.stop()
        transportController.stopAll()
        masterPeakController.resetDisplayAndNativeHold()
        playheadTransport.stopAndResetToZero()
        emitMessage(R.string.error_recording_stopped_storage)
    }

    fun releaseOnCleared() {
        recordingStorageMonitor.stop()
        if (!engineSessionAcquired) return
        engineSessionAcquired = false
        audioIoScope.scope.launch {
            transportController.stopAll()
            audioEngineSession.release {
                withAudioIo(dispatchers, "PlaybackPort.release") {
                    playback.release()
                }
            }
            withContext(dispatchers.main) {
                playheadTransport.stopAndResetToZero()
                masterPeakController.clearDisplayOnTeardown()
            }
        }
    }

    fun setPlayheadNativePollEnabledForTests(enabled: Boolean) {
        playheadTransport.nativePollEnabled = enabled
    }

    fun setRecordingStorageMonitorEnabledForTests(enabled: Boolean) {
        recordingStorageMonitorEnabledForTests = enabled
    }

    fun setMasterPeakPollEnabledForTests(enabled: Boolean) {
        masterPeakController.setPollEnabledForTests(enabled)
    }

    fun advancePlayheadNativeTransportForTests(positionMs: Long) {
        playheadTransport.setNativeTransportPositionForTests(positionMs)
    }

    private fun startRecordingStorageMonitor(activeProjectId: String) {
        if (!recordingStorageMonitorEnabledForTests) return
        val directoryPath = audioFilePathProvider.projectRecordingDirectory(activeProjectId)
        if (directoryPath == null) return
        recordingStorageMonitor.start(
            projectDirectoryPath = directoryPath,
            isRecordingActive = { recordingSession.hasActiveRecordingTake() },
        ) {
            performStopRecordingForStorageExhaustion()
        }
    }
}
