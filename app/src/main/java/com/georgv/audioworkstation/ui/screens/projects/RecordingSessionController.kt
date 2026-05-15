package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
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

    fun hasActiveRecordingTake(): Boolean = _recordingTrackId.value != null

    fun isStartupInFlight(): Boolean = _recordingStartup.value

    fun armRecordingStartup() {
        _recordingStartup.value = true
    }

    fun launchRecordPressed(
        projectId: String,
        projectName: String,
        ensureProject: suspend (String, String) -> ProjectEntity?,
        visibleTrackCount: () -> Int,
        persistRecordingRow: suspend (TrackEntity) -> Unit,
        notifyEngineStartFailed: () -> Unit,
        notifyPersistFailed: () -> Unit,
    ) {
        scope.launch {
            executeRecordPressed(
                projectId = projectId,
                projectName = projectName,
                ensureProject = ensureProject,
                visibleTrackCount = visibleTrackCount,
                persistRecordingRow = persistRecordingRow,
                notifyEngineStartFailed = notifyEngineStartFailed,
                notifyPersistFailed = notifyPersistFailed,
            )
        }
    }

    /** Inlined legacy [ProjectViewModel.onRecordPressed] coroutine body (same try/finally and rollback). */
    suspend fun executeRecordPressed(
        projectId: String,
        projectName: String,
        ensureProject: suspend (String, String) -> ProjectEntity?,
        visibleTrackCount: () -> Int,
        persistRecordingRow: suspend (TrackEntity) -> Unit,
        notifyEngineStartFailed: () -> Unit,
        notifyPersistFailed: () -> Unit,
    ) {
        try {
            val currentProject = ensureProject(projectId, projectName) ?: run {
                _recordingStartup.value = false
                return
            }

            val newTrack =
                when (
                    val startOutcome =
                        recordingCoordinator.beginRecording(
                            projectId = projectId,
                            project = currentProject,
                            visibleTrackCount = visibleTrackCount(),
                        )
                ) {
                    RecordingStartOutcome.EngineStartFailed -> {
                        _recordingStartup.value = false
                        notifyEngineStartFailed()
                        return
                    }
                    is RecordingStartOutcome.ReadyToPersistRecordingRow -> startOutcome.newTrack
                }

            _optimisticRecordingTrack.value = newTrack
            _recordingTrackId.value = newTrack.id
            _recordingStartup.value = false

            try {
                persistRecordingRow(newTrack)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _optimisticRecordingTrack.value = null
                _recordingTrackId.value = null
                _recordingStartup.value = false
                audioController.stopRecording()
                notifyPersistFailed()
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
