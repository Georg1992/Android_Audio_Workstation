package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.RecordingPunchContext
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
        notifyEngineStartFailed: () -> Unit,
        notifyPersistFailed: () -> Unit,
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
        notifyEngineStartFailed: () -> Unit,
        notifyPersistFailed: () -> Unit,
        storagePrecheck: suspend (ProjectEntity) -> Boolean,
        notifyStorageStartBlocked: () -> Unit,
        recordTargetTrack: TrackEntity? = null,
        onPendingTrackAllocated: suspend (TrackEntity) -> Boolean,
        onRecordingTransportReady: (Long) -> Unit = {},
    ) {
        try {
            val currentProject = ensureProject(projectId, projectName) ?: run {
                _recordingStartup.value = false
                return
            }

            if (!storagePrecheck(currentProject)) {
                _recordingStartup.value = false
                notifyStorageStartBlocked()
                return
            }

            val preparedExistingTrack =
                recordTargetTrack?.let { target ->
                    recordingCoordinator.prepareExistingTrackForRecording(
                        track = target,
                        playheadPositionMs = timelineStartOffsetMs,
                    )
                }

            val pendingTrack =
                preparedExistingTrack?.track
                    ?: recordingCoordinator.allocatePendingRecordingTrack(
                        projectId = projectId,
                        visibleTrackCount = visibleTrackCount(),
                        timelineStartOffsetMs = timelineStartOffsetMs,
                    )

            _optimisticRecordingTrack.value = pendingTrack.copy(isRecording = true)
            _recordingTrackId.value = pendingTrack.id
            _recordingStartup.value = false

            if (!onPendingTrackAllocated(pendingTrack)) {
                _optimisticRecordingTrack.value = null
                _recordingTrackId.value = null
                _recordingStartup.value = false
                return
            }

            val startOutcome =
                recordingCoordinator.startEngineForAllocatedTrack(
                    project = currentProject,
                    pendingTrack = pendingTrack,
                    punchRecording = preparedExistingTrack,
                )

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

            try {
                persistRecordingRow(newTrack)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _optimisticRecordingTrack.value = null
                _recordingTrackId.value = null
                _recordingStartup.value = false
                recordingCoordinator.discardPunchRecordingTempFile(_punchRecordingContext.value)
                clearPunchRecordingContext()
                audioController.stopRecording()
                notifyPersistFailed()
                return
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
