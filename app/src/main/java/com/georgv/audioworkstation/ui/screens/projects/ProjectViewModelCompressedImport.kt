package com.georgv.audioworkstation.ui.screens.projects

import androidx.annotation.StringRes
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.Mp3ImportTiming
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.audio.formatSampleRateLabel
import com.georgv.audioworkstation.core.audio.toUiMessage
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

fun ProjectViewModel.confirmImportWithResampling(projectId: String) {
    val pending = pendingCompressedImport ?: return
    pendingCompressedImport = null
    sampleRateMismatchDialog.value = null
    Mp3ImportTiming.recordSampleRateMismatchChoice("resample")
    viewModelScope.launch {
        bind(projectId)
        val currentProject = loadProjectForImport(projectId) ?: run {
            Mp3ImportTiming.endSession("project_unavailable")
            return@launch
        }
        startCompressedImportAfterUserChoice(
            projectId = projectId,
            project = currentProject,
            pending = pending,
        )
    }
}

fun ProjectViewModel.confirmCreateProjectForImport() {
    val pending = pendingCompressedImport ?: return
    pendingCompressedImport = null
    sampleRateMismatchDialog.value = null
    viewModelScope.launch {
        when (val outcome = importCoordinator.createProjectForPendingImport(pending)) {
            CreateProjectForImportOutcome.UnsupportedSampleRate -> {
                Mp3ImportTiming.recordSampleRateMismatchChoice("create_project")
                Mp3ImportTiming.endSession("unsupported_source_sample_rate")
                emitImportUiMessage(R.string.error_import_unsupported_source_sample_rate)
            }
            CreateProjectForImportOutcome.Failed -> {
                Mp3ImportTiming.recordSampleRateMismatchChoice("create_project")
                Mp3ImportTiming.endSession("create_project_failed")
                emitImportUiMessage(R.string.error_create_project_failed)
            }
            is CreateProjectForImportOutcome.Success -> {
                Mp3ImportTiming.recordSampleRateMismatchChoice(
                    choice = "create_project",
                    newProjectSampleRateHz = outcome.project.sampleRate,
                )
                Mp3ImportTiming.endSession("create_project")
                pendingImportRegistry.assign(outcome.project.id, pending)
                openProjectRequestEvents.trySend(outcome.project.id)
            }
        }
    }
}

fun ProjectViewModel.cancelSampleRateMismatchImport() {
    pendingCompressedImport = null
    sampleRateMismatchDialog.value = null
    Mp3ImportTiming.recordSampleRateMismatchChoice("cancel")
    Mp3ImportTiming.endSession("cancelled")
}

internal fun ProjectViewModel.tryStartRegistryPendingCompressedImport(projectId: String) {
    val pending = pendingImportRegistry.consume(projectId) ?: return
    viewModelScope.launch {
        val project = loadProjectForImport(projectId) ?: return@launch
        startCompressedImportAfterUserChoice(
            projectId = projectId,
            project = project,
            pending = pending,
        )
    }
}

internal fun ProjectViewModel.clearSampleRateMismatchPromptState() {
    pendingCompressedImport = null
    sampleRateMismatchDialog.value = null
}

internal suspend fun ProjectViewModel.handleImportPrepareOutcome(outcome: ProjectAudioImportOutcome) {
    when (outcome) {
        ProjectAudioImportOutcome.StorageUnavailable ->
            emitImportUiMessage(R.string.error_import_storage_unavailable)
        is ProjectAudioImportOutcome.ImportRejected ->
            emitImportUiMessage(outcome.failure.toUiMessage())
        is ProjectAudioImportOutcome.ReadyToPersist ->
            importDbActions.run(R.string.error_save_imported_track_failed) {
                importRepo.upsertTracks(listOf(outcome.importedTrack))
            }
        is ProjectAudioImportOutcome.SampleRateMismatchRequired -> {
            pendingCompressedImport = outcome.pending
            sampleRateMismatchDialog.value =
                SampleRateMismatchDialogState(
                    sourceSampleRateHz = outcome.sourceSampleRateHz,
                    projectSampleRateHz = outcome.projectSampleRateHz,
                    sourceSampleRateLabel = formatSampleRateLabel(outcome.sourceSampleRateHz),
                    projectSampleRateLabel = formatSampleRateLabel(outcome.projectSampleRateHz),
                    createProjectSampleRateLabel = formatSampleRateLabel(outcome.sourceSampleRateHz),
                )
        }
        is ProjectAudioImportOutcome.ImportStarted ->
            persistAndLaunchBackgroundImport(outcome)
    }
}

internal suspend fun ProjectViewModel.startCompressedImportAfterUserChoice(
    projectId: String,
    project: ProjectEntity,
    pending: PendingCompressedImport,
) {
    if (recordingSession.hasActiveRecordingTake() || recordingSession.isStartupInFlight()) {
        emitImportUiMessage(R.string.error_stop_recording_to_import)
        return
    }
    when (
        val outcome =
            importCoordinator.startCompressedImportFromPending(
                projectId = projectId,
                project = project,
                visibleTrackCount = uiState.value.tracks.size,
                pending = pending,
            )
    ) {
        ProjectAudioImportOutcome.StorageUnavailable ->
            emitImportUiMessage(R.string.error_import_storage_unavailable)
        is ProjectAudioImportOutcome.ImportRejected ->
            emitImportUiMessage(outcome.failure.toUiMessage())
        is ProjectAudioImportOutcome.ImportStarted ->
            persistAndLaunchBackgroundImport(outcome)
        else -> Unit
    }
}

internal suspend fun ProjectViewModel.persistAndLaunchBackgroundImport(
    outcome: ProjectAudioImportOutcome.ImportStarted,
) {
    recordTargetTrackId.value = null
    selectedTrackIds.value = selectedTrackIds.value - outcome.importingTrack.id
    importUiCoordinator.beginImport(outcome.importingTrack.id)
    Mp3ImportTiming.startStage("db_upsert_importing")
    importDbActions.run(R.string.error_save_imported_track_failed) {
        importRepo.upsertTracks(listOf(outcome.importingTrack))
    }
    Mp3ImportTiming.stopStage("db_upsert_importing")
    launchBackgroundImportJob(outcome)
}

internal fun ProjectViewModel.launchBackgroundImportJob(started: ProjectAudioImportOutcome.ImportStarted) {
    val trackId = started.importingTrack.id
    importJobs[trackId]?.cancel()
    importJobs[trackId] =
        viewModelScope.launch {
            try {
                when (
                    val outcome =
                        importCoordinator.executeBackgroundImport(
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
                        importDbActions.run(R.string.error_save_imported_track_failed) {
                            importRepo.upsertTracks(listOf(outcome.importedTrack))
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
                    is ProjectAudioImportOutcome.ImportRejected ->
                        handleBackgroundImportRejected(started, trackId, outcome)
                    else -> Unit
                }
            } catch (cancel: CancellationException) {
                handleBackgroundImportCancellation(started, trackId, cancel)
                throw cancel
            } finally {
                importJobs.remove(trackId)
            }
        }
}

internal fun ProjectViewModel.emitImportUiMessage(message: UiMessage) {
    importMessageChannel.trySend(message)
}

internal fun ProjectViewModel.emitImportUiMessage(@StringRes resId: Int) {
    emitImportUiMessage(UiMessage(resId))
}

internal suspend fun ProjectViewModel.ensureProjectForImport(projectId: String): ProjectEntity? =
    try {
        importRepo.ensureProject(projectId, "New Project")
    } catch (cancel: kotlinx.coroutines.CancellationException) {
        throw cancel
    } catch (error: Exception) {
        com.georgv.audioworkstation.core.util.logWarning(ProjectViewModel.TAG, "createProject failed", error)
        emitImportUiMessage(R.string.error_create_project_failed)
        null
    }

internal suspend fun ProjectViewModel.loadProjectForImport(projectId: String): ProjectEntity? =
    try {
        importRepo.observeProject(projectId).first()
            ?: run {
                emitImportUiMessage(R.string.error_project_audio_unavailable)
                null
            }
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        com.georgv.audioworkstation.core.util.logWarning(ProjectViewModel.TAG, "loadProject failed", error)
        emitImportUiMessage(R.string.error_project_audio_unavailable)
        null
    }
