package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.session.ProjectAudioImportOutcome
import com.georgv.audioworkstation.core.audio.Mp3ImportTiming
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.core.audio.toUiMessage
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import java.io.File
import kotlinx.coroutines.CancellationException

internal suspend fun ProjectViewModel.removeCancelledImportTrack(
    trackId: String,
    track: TrackEntity,
    destinationPath: String,
    error: Throwable? = null,
) {
    if (destinationPath.isNotBlank()) {
        withIo(appDispatchers, "import cancel delete partial wav") {
            File(destinationPath).delete()
        }
    }
    Mp3ImportTiming.recordFailure(
        stage = "background_import_cancelled",
        error = error,
        partialWavDeleted = destinationPath.isNotBlank(),
    )
    Mp3ImportTiming.endSession("cancelled")
    val remainingTracks =
        uiState.value.tracks
            .filter { it.id != trackId }
            .mapIndexed { index, remainingTrack -> remainingTrack.copy(position = index) }
    val rollbackSelection = importSession.cancelSelectionRollback.remove(trackId)
    importDbActions.runWithRollback(
        errorResId = R.string.error_delete_track_failed,
        rollback = { rollbackSelection?.let { selectedTrackIds.value = it } },
    ) {
        importRepo.deleteTrack(track, remainingTracks)
    }
    importSession.jobs.remove(trackId)
}

internal suspend fun ProjectViewModel.handleBackgroundImportRejected(
    started: ProjectAudioImportOutcome.ImportStarted,
    trackId: String,
    outcome: ProjectAudioImportOutcome.ImportRejected,
) {
    withIo(appDispatchers, "import reject delete partial wav") {
        File(started.session.destinationPath).delete()
    }
    importDbActions.run(R.string.error_save_imported_track_failed) {
        importRepo.upsertTracks(
            listOf(started.importingTrack.copy(importStatus = TrackImportStatus.FAILED)),
        )
    }
    importUiCoordinator.clear(trackId)
    Mp3ImportTiming.endSession("failed")
    emitImportUiMessage(outcome.failure.toUiMessage())
}

internal suspend fun ProjectViewModel.handleBackgroundImportCancellation(
    started: ProjectAudioImportOutcome.ImportStarted,
    trackId: String,
    cancel: CancellationException,
) {
    val userCancelled = importSession.userCancelledTrackIds.remove(trackId)
    if (userCancelled) {
        removeCancelledImportTrack(
            trackId = trackId,
            track = started.importingTrack,
            destinationPath = started.session.destinationPath,
            error = cancel,
        )
    } else {
        withIo(appDispatchers, "import background cancel delete partial wav") {
            File(started.session.destinationPath).delete()
        }
        Mp3ImportTiming.recordFailure(
            stage = "background_import_cancelled",
            error = cancel,
            partialWavDeleted = true,
        )
        importDbActions.run(R.string.error_save_imported_track_failed) {
            importRepo.upsertTracks(
                listOf(started.importingTrack.copy(importStatus = TrackImportStatus.FAILED)),
            )
        }
        Mp3ImportTiming.endSession("cancelled")
    }
    importUiCoordinator.clear(trackId)
}
