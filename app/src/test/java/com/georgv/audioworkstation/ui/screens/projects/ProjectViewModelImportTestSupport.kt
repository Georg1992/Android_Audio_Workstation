package com.georgv.audioworkstation.ui.screens.projects

import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

/** Simulates an in-flight background decode without running MediaCodec. */
internal fun ProjectViewModel.registerActiveImportForTests(
    repo: ProjectRepository,
    importingTrack: TrackEntity,
) {
    importUiCoordinator.beginImport(importingTrack.id)
    importSession.jobs[importingTrack.id]?.cancel()
    importSession.jobs[importingTrack.id] =
        viewModelScope.launch {
            try {
                awaitCancellation()
            } finally {
                importSession.jobs.remove(importingTrack.id)
            }
        }
    viewModelScope.launch {
        repo.upsertTracks(listOf(importingTrack.copy(importStatus = TrackImportStatus.IMPORTING)))
    }
}
