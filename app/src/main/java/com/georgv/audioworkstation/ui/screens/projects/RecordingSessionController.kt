package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.RecordingPunchContext
import com.georgv.audioworkstation.core.audio.capability.AudioCapabilityProfileResolver
import com.georgv.audioworkstation.core.audio.RecordingLatencyCalibrationLog
import com.georgv.audioworkstation.core.audio.isNormalOverdubRecording
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForPlayback
import com.georgv.audioworkstation.core.track.recordingClipTimelineStartMs
import com.georgv.audioworkstation.ui.diagnostics.QuickRecordDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Owns recording *transport/session* MutableStateFlows only: pending take id, startup guard, optimistic row.
 *
 * Persistence, user-visible messages, and [ProjectUiState] combine remain in [ProjectViewModel].
 * [ProjectTransportController] drives teardown ordering against this session.
 */
class RecordingSessionController(
    private val scope: CoroutineScope,
    private val audioController: AudioController,
    private val recordingCoordinator: ProjectRecordingCoordinator,
    private val dispatchers: AppDispatchers,
    private val capabilityProfileResolver: AudioCapabilityProfileResolver,
) {
    private val _recordingTrackId = MutableStateFlow<String?>(null)
    val recordingTrackId: StateFlow<String?> = _recordingTrackId.asStateFlow()

    private val _recordingStartup = MutableStateFlow(false)
    val recordingStartup: StateFlow<Boolean> = _recordingStartup.asStateFlow()

    private val _optimisticRecordingTrack = MutableStateFlow<TrackEntity?>(null)
    val optimisticRecordingTrack: StateFlow<TrackEntity?> = _optimisticRecordingTrack.asStateFlow()

    private val _recordingOutputWavPath = MutableStateFlow<String?>(null)

    private val _punchRecordingContext = MutableStateFlow<RecordingPunchContext?>(null)

    private val _activeNormalOverdubContext = MutableStateFlow<ActiveNormalOverdubContext?>(null)

    fun punchRecordingContext(): RecordingPunchContext? = _punchRecordingContext.value

    fun activeNormalOverdubContext(): ActiveNormalOverdubContext? = _activeNormalOverdubContext.value

    fun hasActiveRecordingTake(): Boolean = _recordingTrackId.value != null

    fun isStartupInFlight(): Boolean = _recordingStartup.value

    fun armRecordingStartup() {
        _recordingStartup.value = true
    }

    fun launchRecordPressed(
        projectId: String,
        projectName: String,
        timelineStartOffsetMs: Long,
        ensureProject: suspend (String, String) -> ProjectEntity?,
        visibleTrackCount: () -> Int,
        persistRecordingRow: suspend (TrackEntity) -> Unit,
        notifyEngineStartFailed: suspend () -> Unit,
        notifyPersistFailed: suspend () -> Unit,
        storagePrecheck: suspend (ProjectEntity) -> Boolean,
        notifyStorageStartBlocked: () -> Unit,
        recordTargetTrack: TrackEntity? = null,
        overdubPlaybackTracks: List<TrackEntity> = emptyList(),
        overdubPlaybackStartMs: Long = timelineStartOffsetMs,
        onPendingTrackAllocated: suspend (TrackEntity) -> Boolean = { true },
        onRecordingTransportReady: (Long) -> Unit = {},
    ) {
        scope.launch {
            executeRecordPressed(
                projectId = projectId,
                projectName = projectName,
                timelineStartOffsetMs = timelineStartOffsetMs,
                ensureProject = ensureProject,
                visibleTrackCount = visibleTrackCount,
                persistRecordingRow = persistRecordingRow,
                notifyEngineStartFailed = notifyEngineStartFailed,
                notifyPersistFailed = notifyPersistFailed,
                storagePrecheck = storagePrecheck,
                notifyStorageStartBlocked = notifyStorageStartBlocked,
                recordTargetTrack = recordTargetTrack,
                overdubPlaybackTracks = overdubPlaybackTracks,
                overdubPlaybackStartMs = overdubPlaybackStartMs,
                onPendingTrackAllocated = onPendingTrackAllocated,
                onRecordingTransportReady = onRecordingTransportReady,
            )
        }
    }

    /**
     * Recording entry: publish an optimistic row immediately after pending allocation, then start
     * the native recorder and persist — same rollback/stop semantics as the pre–Phase-3 flow except
     * for earlier UI updates.
     */
    suspend fun executeRecordPressed(
        projectId: String,
        projectName: String,
        timelineStartOffsetMs: Long,
        ensureProject: suspend (String, String) -> ProjectEntity?,
        visibleTrackCount: () -> Int,
        persistRecordingRow: suspend (TrackEntity) -> Unit,
        notifyEngineStartFailed: suspend () -> Unit,
        notifyPersistFailed: suspend () -> Unit,
        storagePrecheck: suspend (ProjectEntity) -> Boolean,
        notifyStorageStartBlocked: () -> Unit,
        recordTargetTrack: TrackEntity? = null,
        overdubPlaybackTracks: List<TrackEntity> = emptyList(),
        overdubPlaybackStartMs: Long = timelineStartOffsetMs,
        onPendingTrackAllocated: suspend (TrackEntity) -> Boolean,
        onRecordingTransportReady: (Long) -> Unit = {},
    ) {
        val quickActive = QuickRecordDiagnostics.isActiveFor(projectId)
        val sessionStartMs = android.os.SystemClock.uptimeMillis()
        if (quickActive) {
            QuickRecordDiagnostics.logStepStart("QuickRecordBootstrap recording session", projectId)
        }
        try {
            val ensureStartMs = android.os.SystemClock.uptimeMillis()
            if (quickActive) {
                QuickRecordDiagnostics.logStepStart("ensureProject for recording", projectId)
            }
            val currentProject =
                withIo(dispatchers, "ensureProject for recording") {
                    ensureProject(projectId, projectName)
                } ?: run {
                if (quickActive) {
                    QuickRecordDiagnostics.logStepEnd("ensureProject for recording", ensureStartMs, projectId, "failed")
                }
                _recordingStartup.value = false
                return
            }
            if (quickActive) {
                QuickRecordDiagnostics.logStepEnd("ensureProject for recording", ensureStartMs, projectId)
            }

            val precheckStartMs = android.os.SystemClock.uptimeMillis()
            if (quickActive) {
                QuickRecordDiagnostics.logStepStart("recording storage precheck", projectId)
            }
            val storageOk =
                withIo(dispatchers, "recording storage precheck") {
                    storagePrecheck(currentProject)
                }
            if (!storageOk) {
                if (quickActive) {
                    QuickRecordDiagnostics.logStepEnd("recording storage precheck", precheckStartMs, projectId, "blocked")
                }
                _recordingStartup.value = false
                notifyStorageStartBlocked()
                return
            }
            if (quickActive) {
                QuickRecordDiagnostics.logStepEnd("recording storage precheck", precheckStartMs, projectId)
            }

            val allocateStartMs = android.os.SystemClock.uptimeMillis()
            if (quickActive) {
                QuickRecordDiagnostics.logStepStart("allocate pending recording track", projectId)
            }
            val preparedExistingTrack =
                recordTargetTrack?.let { target ->
                    recordingCoordinator.prepareExistingTrackForRecording(
                        track = target,
                        playheadPositionMs = timelineStartOffsetMs,
                    )
                }

            val recordingTimelineStartOffsetMs =
                recordingClipTimelineStartMs(
                    playheadMs = timelineStartOffsetMs,
                    overdubPlaybackStartMs = overdubPlaybackStartMs,
                    hasOverdubBacking = overdubPlaybackTracks.isNotEmpty(),
                )
            val pendingTrack =
                withIo(dispatchers, "allocate pending recording track") {
                    preparedExistingTrack?.track
                        ?: recordingCoordinator.allocatePendingRecordingTrack(
                            projectId = projectId,
                            visibleTrackCount = visibleTrackCount(),
                            timelineStartOffsetMs = recordingTimelineStartOffsetMs,
                        )
                }
            if (quickActive) {
                QuickRecordDiagnostics.logStepEnd(
                    "allocate pending recording track",
                    allocateStartMs,
                    projectId,
                    "trackId=${pendingTrack.id}",
                )
            }

            _optimisticRecordingTrack.value = pendingTrack.copy(isRecording = true)
            _recordingTrackId.value = pendingTrack.id
            _recordingStartup.value = false

            val overdubStartMs = android.os.SystemClock.uptimeMillis()
            if (quickActive) {
                QuickRecordDiagnostics.logStepStart("onPendingTrackAllocated", projectId)
            }
            if (!onPendingTrackAllocated(pendingTrack)) {
                if (quickActive) {
                    QuickRecordDiagnostics.logStepEnd("onPendingTrackAllocated", overdubStartMs, projectId, "aborted")
                }
                _optimisticRecordingTrack.value = null
                _recordingTrackId.value = null
                _recordingStartup.value = false
                return
            }
            if (quickActive) {
                QuickRecordDiagnostics.logStepEnd("onPendingTrackAllocated", overdubStartMs, projectId)
            }

            val overdubLanes =
                overdubPlaybackTracks
                    .filter { it.id != pendingTrack.id }
                    .filter { it.wavFilePath.isNotBlank() }
            val overdubPlaybackSpec =
                overdubLanes
                    .takeIf { it.isNotEmpty() }
                    ?.let { lanes ->
                        currentProject.toMultiPlaybackSpec(lanes)?.copy(
                            startPositionMs = overdubPlaybackStartMs,
                            sessionTimelineEndMs = sessionTimelineEndMsForPlayback(lanes),
                        )
                    }
            if (overdubLanes.isNotEmpty() && overdubPlaybackSpec == null) {
                _optimisticRecordingTrack.value = null
                _recordingTrackId.value = null
                _recordingStartup.value = false
                notifyEngineStartFailed()
                return
            }

            val isNormalOverdub =
                isNormalOverdubRecording(
                    overdubLaneCount = overdubLanes.size,
                    anyLoopEnabledInBacking = overdubPlaybackSpec?.lanes?.any { it.loopEnabled } == true,
                )
            _activeNormalOverdubContext.value =
                if (isNormalOverdub) {
                    val backingTrack = overdubLanes.first()
                    ActiveNormalOverdubContext(
                        backingTrackId = backingTrack.id,
                        backingWavPath = backingTrack.wavFilePath,
                        backingTimelineStartOffsetMs = backingTrack.timelineStartOffsetMs,
                        playbackStartMs = overdubPlaybackStartMs,
                    )
                } else {
                    null
                }
            if (isNormalOverdub) {
                val audioCapability =
                    withIo(dispatchers, "resolve audio capability profile") {
                        capabilityProfileResolver.resolve(currentProject.sampleRate)
                    }
                RecordingLatencyCalibrationLog.logNormalOverdubStart(audioCapability)
                audioController.configureSessionTransportLatencies(
                    inputLatencyMs =
                        audioCapability.inputHalLatencyMs
                            ?: audioCapability.inputCaptureDelayMs
                            ?: 0.0,
                    outputLatencyMs = audioCapability.outputLatencyMs ?: 0.0,
                )
            }

            val engineStartMs = android.os.SystemClock.uptimeMillis()
            val startOutcome =
                QuickRecordDiagnostics.traceSection("QuickRecordStartRecording", projectId) {
                    if (quickActive) {
                        QuickRecordDiagnostics.logStepStart("audio engine startRecording", projectId)
                    }
                    recordingCoordinator.startEngineForAllocatedTrack(
                        project = currentProject,
                        pendingTrack = pendingTrack,
                        punchRecording = preparedExistingTrack,
                        overdubPlaybackSpec = overdubPlaybackSpec,
                    ).also {
                        if (quickActive) {
                            QuickRecordDiagnostics.logStepEnd("audio engine startRecording", engineStartMs, projectId)
                        }
                    }
                }

            val newTrack =
                when (startOutcome) {
                    RecordingStartOutcome.EngineStartFailed -> {
                        _optimisticRecordingTrack.value = null
                        _recordingTrackId.value = null
                        _recordingStartup.value = false
                        notifyEngineStartFailed()
                        return
                    }
                    is RecordingStartOutcome.ReadyToPersistRecordingRow -> {
                        _punchRecordingContext.value = startOutcome.punchContext
                        startOutcome.newTrack
                    }
                }

            _optimisticRecordingTrack.value = newTrack
            _recordingOutputWavPath.value = newTrack.wavFilePath.takeIf { it.isNotBlank() }

            onRecordingTransportReady(overdubPlaybackStartMs)

            val persistStartMs = android.os.SystemClock.uptimeMillis()
            if (quickActive) {
                QuickRecordDiagnostics.logStepStart("persist recording row", projectId, "trackId=${newTrack.id}")
            }
            try {
                withIo(dispatchers, "persist recording row") {
                    persistRecordingRow(newTrack)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                if (quickActive) {
                    QuickRecordDiagnostics.logStepEnd("persist recording row", persistStartMs, projectId, "failed")
                }
                rollbackFailedRecordingPersist(notifyPersistFailed)
                return
            }
            if (quickActive) {
                QuickRecordDiagnostics.logStepEnd("persist recording row", persistStartMs, projectId, "trackId=${newTrack.id}")
                QuickRecordDiagnostics.logStepEnd("QuickRecordBootstrap recording session", sessionStartMs, projectId)
            }
        } finally {
            if (_recordingTrackId.value == null && _recordingStartup.value) {
                _recordingStartup.value = false
            }
        }
    }

    /** Drop the appended optimistic row once [projectTracks] contains the same id (avoids list duplicates). */
    fun clearOptimisticRecordingRow() {
        _optimisticRecordingTrack.value = null
    }

    /** Clears recording session markers when the bound project id changes ([ProjectViewModel.bind]). */
    fun resetWhenBoundProjectChanges() {
        recordingCoordinator.discardPunchRecordingTempFile(_punchRecordingContext.value)
        clearPunchRecordingContext()
        _recordingTrackId.value = null
        _optimisticRecordingTrack.value = null
        _recordingOutputWavPath.value = null
        _activeNormalOverdubContext.value = null
        _recordingStartup.value = false
    }

    /** Transport stop step 2 — matches historical position before engine [AudioController.stopRecording]. */
    fun clearStartupFlagForTransportStop() {
        _recordingStartup.value = false
    }

    fun activeRecordingTrackIdForTransport(): String? = _recordingTrackId.value

    /** Output WAV path for the active take — set when native capture starts, cleared on transport stop. */
    fun activeRecordingWavPathForTransport(): String? =
        _recordingOutputWavPath.value?.takeIf { it.isNotBlank() }
            ?: _optimisticRecordingTrack.value?.wavFilePath?.takeIf { it.isNotBlank() }

    /** Transport stop steps 5–6 — clear recording row markers (after engine stops). */
    fun clearRecordingTransportMarkers() {
        _recordingTrackId.value = null
        _optimisticRecordingTrack.value = null
        _recordingOutputWavPath.value = null
        _activeNormalOverdubContext.value = null
    }

    fun clearPunchRecordingContext() {
        _punchRecordingContext.value = null
    }

    private suspend fun rollbackFailedRecordingPersist(notifyPersistFailed: suspend () -> Unit) {
        _optimisticRecordingTrack.value = null
        _recordingTrackId.value = null
        _recordingOutputWavPath.value = null
        _recordingStartup.value = false
        withIo(dispatchers, "discard punch recording temp file") {
            recordingCoordinator.discardPunchRecordingTempFile(_punchRecordingContext.value)
        }
        clearPunchRecordingContext()
        withAudioIo(dispatchers, "AudioController.stopRecording rollback") {
            audioController.stopRecording()
        }
        notifyPersistFailed.invoke()
    }

    /** Same-module unit tests: seed flows without running the full record pipeline. */
    internal fun seedRecordingStateForTests(
        recordingId: String?,
        optimistic: TrackEntity?,
        startup: Boolean,
    ) {
        _recordingTrackId.value = recordingId
        _optimisticRecordingTrack.value = optimistic
        _recordingStartup.value = startup
    }
}
