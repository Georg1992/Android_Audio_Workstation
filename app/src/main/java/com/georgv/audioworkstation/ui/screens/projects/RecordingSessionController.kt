package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.RecordingPunchContext
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
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
) {
    private val _recordingTrackId = MutableStateFlow<String?>(null)
    val recordingTrackId: StateFlow<String?> = _recordingTrackId.asStateFlow()

    private val _recordingStartup = MutableStateFlow(false)
    val recordingStartup: StateFlow<Boolean> = _recordingStartup.asStateFlow()

    private val _optimisticRecordingTrack = MutableStateFlow<TrackEntity?>(null)
    val optimisticRecordingTrack: StateFlow<TrackEntity?> = _optimisticRecordingTrack.asStateFlow()

    private val _punchRecordingContext = MutableStateFlow<RecordingPunchContext?>(null)

    fun punchRecordingContext(): RecordingPunchContext? = _punchRecordingContext.value

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

            val pendingTrack =
                withIo(dispatchers, "allocate pending recording track") {
                    preparedExistingTrack?.track
                        ?: recordingCoordinator.allocatePendingRecordingTrack(
                            projectId = projectId,
                            visibleTrackCount = visibleTrackCount(),
                            timelineStartOffsetMs = timelineStartOffsetMs,
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

            onRecordingTransportReady(timelineStartOffsetMs)

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
        _recordingStartup.value = false
    }

    /** Transport stop step 2 — matches historical position before engine [AudioController.stopRecording]. */
    fun clearStartupFlagForTransportStop() {
        _recordingStartup.value = false
    }

    fun activeRecordingTrackIdForTransport(): String? = _recordingTrackId.value

    /** Transport stop steps 5–6 — clear recording row markers (after engine stops). */
    fun clearRecordingTransportMarkers() {
        _recordingTrackId.value = null
        _optimisticRecordingTrack.value = null
    }

    fun clearPunchRecordingContext() {
        _punchRecordingContext.value = null
    }

    private suspend fun rollbackFailedRecordingPersist(notifyPersistFailed: suspend () -> Unit) {
        _optimisticRecordingTrack.value = null
        _recordingTrackId.value = null
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
