package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import java.io.File
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** [projectTracks] is fed by [SharingStarted.WhileSubscribed]; wait until it matches the repo. */
internal suspend fun awaitProjectTracksSynced(
    repo: ProjectRepository,
    projectTracks: StateFlow<List<TrackEntity>>,
    projectId: String,
) {
    val expectedTracks = repo.observeTracks(projectId).first()
    projectTracks.first { it == expectedTracks }
}

internal suspend fun recoverStaleImports(
    repo: ProjectRepository,
    projectId: String,
    importJobs: Map<String, Job>,
) {
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
