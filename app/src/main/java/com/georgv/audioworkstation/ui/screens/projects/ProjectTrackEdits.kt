package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioParameterCommandQueue
import com.georgv.audioworkstation.core.audio.GainRange
import com.georgv.audioworkstation.core.audio.PanRange
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.diagnostics.ThreadingDiagnostics
import com.georgv.audioworkstation.core.session.PlaybackSessionController
import com.georgv.audioworkstation.core.session.ProjectDbActionRunner
import com.georgv.audioworkstation.core.session.RecordingSessionController
import com.georgv.audioworkstation.core.session.ScopePlaybackCoordinator
import com.georgv.audioworkstation.core.track.activeMixScopeTrackIds
import com.georgv.audioworkstation.core.track.clampLoopRegionMs
import com.georgv.audioworkstation.core.track.reconcileInScopeLoopRegions
import com.georgv.audioworkstation.core.track.sourceDurationMs
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.toProjectNameUiMessage
import com.georgv.audioworkstation.core.validation.toTrackNameUiMessage
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.reorder.OptimisticTrackOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Track list edits for one bound project. Transport stop, seek, and record stay on the session. */
internal class ProjectTrackEdits(
    private val scope: CoroutineScope,
    private val repo: ProjectRepository,
    private val playbackSession: PlaybackSessionController,
    private val recordingSession: RecordingSessionController,
    private val scopePlayback: ScopePlaybackCoordinator,
    private val audioParameterQueue: AudioParameterCommandQueue,
    private val dbActions: ProjectDbActionRunner,
    private val projectId: StateFlow<String?>,
    private val selectedTrackIds: MutableStateFlow<Set<String>>,
    private val recordTargetTrackId: MutableStateFlow<String?>,
    private val optimisticTracks: MutableStateFlow<List<TrackEntity>?>,
    private val optimisticTrackGains: MutableStateFlow<Map<String, Float>>,
    private val optimisticTrackPans: MutableStateFlow<Map<String, Float>>,
    private val projectTracks: StateFlow<List<TrackEntity>>,
    private val trackOrderPersistMutex: Mutex,
    private val visibleTracks: () -> List<TrackEntity>,
    private val boundProject: () -> ProjectEntity?,
    private val importInProgress: () -> Boolean,
    private val currentVisibleTracks: () -> List<TrackEntity>,
    private val emitMessage: (UiMessage) -> Unit,
) {
    fun renameProject(newName: String) {
        val pid = projectId.value ?: return
        val normalizedName = when (val validation = validateName(newName)) {
            is NameValidationResult.Invalid -> {
                emitMessage(validation.error.toProjectNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }
        scope.launch {
            dbActions.run(R.string.error_rename_project_failed) {
                val base =
                    boundProject()?.takeIf { it.id == pid }
                        ?: repo.observeProject(pid).first()
                        ?: ProjectEntity(id = pid)
                if (normalizedName == (base.name ?: "").trim()) return@run
                repo.upsertProject(base.copy(name = normalizedName))
            }
        }
    }

    fun deleteTrack(trackId: String) {
        if (recordingSession.recordingTrackId.value == trackId) {
            emitMessage(UiMessage(R.string.error_stop_recording_to_delete_track))
            return
        }
        if (playbackSession.isTrackLoadedInSession(trackId)) {
            emitMessage(UiMessage(R.string.error_stop_playback_to_delete_track))
            return
        }

        val track = visibleTracks().find { it.id == trackId } ?: return
        if (track.importStatus == TrackImportStatus.IMPORTING) return

        val previousSelected = selectedTrackIds.value
        selectedTrackIds.value = selectedTrackIds.value - trackId
        if (recordTargetTrackId.value == trackId) {
            recordTargetTrackId.value = null
        }
        val remainingTracks = visibleTracks()
            .filter { it.id != trackId }
            .mapIndexed { i, t -> t.copy(position = i) }
        scope.launch {
            dbActions.runWithRollback(
                errorResId = R.string.error_delete_track_failed,
                rollback = { selectedTrackIds.value = previousSelected }
            ) {
                repo.deleteTrack(track, remainingTracks)
            }
        }
    }

    fun renameTrack(trackId: String, newName: String) {
        val currentTrack = visibleTracks().find { it.id == trackId } ?: return
        val normalizedName = when (val validation = validateName(newName)) {
            is NameValidationResult.Invalid -> {
                emitMessage(validation.error.toTrackNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }
        if (normalizedName == (currentTrack.name ?: "").trim()) return

        val updatedTrack = currentTrack.copy(name = normalizedName)
        scope.launch {
            dbActions.run(R.string.error_rename_track_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun toggleTrackLoop(trackId: String) {
        if (playbackSession.hasActivePlaybackSession()) return
        val currentTrack = visibleTracks().find { it.id == trackId } ?: return
        val enabling = !currentTrack.isLoop
        if (enabling && recordTargetTrackId.value == trackId) {
            recordTargetTrackId.value = null
        }
        val updatedTrack = currentTrack.copy(isLoop = enabling)
        scope.launch {
            dbActions.run(R.string.error_loop_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun updateTrackLoopRegion(trackId: String, loopStartMs: Long, loopEndMs: Long) {
        if (playbackSession.hasActivePlaybackSession()) return
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        val currentTrack = visibleTracks().find { it.id == trackId } ?: return
        if (!currentTrack.isLoop) return
        val sourceDuration = currentTrack.sourceDurationMs()
        if (sourceDuration <= 0L) return
        val (startMs, endMs) = clampLoopRegionMs(loopStartMs, loopEndMs, sourceDuration)
        val updatedTrack =
            currentTrack.copy(
                loopStartMs = startMs,
                loopEndMs = endMs,
            )
        scope.launch {
            dbActions.run(R.string.error_loop_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun toggleRecordTarget(trackId: String) {
        if (playbackSession.hasActivePlaybackSession()) return
        if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) return
        if (importInProgress()) return
        if (visibleTracks().none { it.id == trackId }) return
        val selecting = recordTargetTrackId.value != trackId
        if (selecting) {
            val currentTrack = visibleTracks().find { it.id == trackId } ?: return
            if (currentTrack.importStatus != TrackImportStatus.READY) return
            if (currentTrack.isLoop) {
                scope.launch {
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

    fun setTrackGain(trackId: String, gain: Float) {
        val contains = visibleTracks().any { it.id == trackId }
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

    fun commitTrackGain(trackId: String, gain: Float) {
        val currentTrack = projectTracks.value.find { it.id == trackId }
        if (currentTrack == null) {
            return
        }
        if (gain == currentTrack.gain) {
            return
        }

        val updatedTrack = currentTrack.copy(gain = gain)
        scope.launch {
            dbActions.run(R.string.error_gain_update_failed) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun setTrackPan(trackId: String, pan: Float) {
        if (!visibleTracks().any { it.id == trackId }) {
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
        scope.launch {
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
                liveTracks = visibleTracks(),
                proposedOrder = orderedTracks,
            )
                ?: return
        optimisticTracks.value = next
    }

    fun persistTrackOrderToDb(projectId: String) {
        if (this.projectId.value != projectId) return
        scope.launch {
            trackOrderPersistMutex.withLock {
                if (this@ProjectTrackEdits.projectId.value != projectId) return@withLock
                val list =
                    visibleTracks().mapIndexed { index, track -> track.copy(position = index) }
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
            scope.launch {
                scopePlayback.onSelectionChangedDuringTransport(next)
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
        scope.launch {
            dbActions.run(R.string.error_loop_update_failed) {
                repo.updateTracks(updates)
            }
        }
    }
}
