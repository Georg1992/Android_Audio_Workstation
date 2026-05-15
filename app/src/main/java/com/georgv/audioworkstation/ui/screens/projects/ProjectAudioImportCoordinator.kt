package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportResult
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.AudioImportTarget
import com.georgv.audioworkstation.core.audio.AudioImporter
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import javax.inject.Inject

/** Result of the audio-import orchestration; the ViewModel maps this to UI messages / DB commits. */
sealed class ProjectAudioImportOutcome {

    /** Output path unavailable (e.g. storage not mounted). Caller shows [com.georgv.audioworkstation.R.string.error_import_storage_unavailable]. */
    data object StorageUnavailable : ProjectAudioImportOutcome()

    data class ImportRejected(val failure: AudioImportResult.Failure) : ProjectAudioImportOutcome()

    /** Persist via [ProjectRepository.upsertTracks]; DB errors use [com.georgv.audioworkstation.R.string.error_save_imported_track_failed]. */
    data class ReadyToPersist(val importedTrack: TrackEntity) : ProjectAudioImportOutcome()
}

/**
 * Deterministic pipeline: allocate pending track slot, resolve output path, import bytes, produce a
 * track row ready for upsert or a typed failure — no Flow / messaging.
 */
class ProjectAudioImportCoordinator @Inject constructor(
    private val repo: ProjectRepository,
    private val audioImporter: AudioImporter,
    private val audioFilePathProvider: AudioFilePathProvider,
) {

    suspend fun run(
        projectId: String,
        project: ProjectEntity,
        visibleTrackCount: Int,
        source: AudioImportSource,
        suggestedName: String?,
    ): ProjectAudioImportOutcome {
        val pendingTrack =
            repo.appendTrackToProject(projectId, "Take ${visibleTrackCount + 1}")
        val destinationPath =
            audioFilePathProvider.trackOutputPath(projectId, pendingTrack.id)
                ?: return ProjectAudioImportOutcome.StorageUnavailable

        val target =
            AudioImportTarget(
                sampleRate = project.sampleRate,
                fileBitDepth = project.fileBitDepth,
                channelMode = pendingTrack.channelMode,
            )
        return when (val result =
            audioImporter.import(source = source, destinationPath = destinationPath, target = target)
        ) {
            is AudioImportResult.Success -> {
                val importedName =
                    suggestedName
                        ?.let { validateName(it) as? NameValidationResult.Valid }
                        ?.normalized
                        ?: "Take ${visibleTrackCount + 1} (imported)"
                val importedTrack =
                    pendingTrack.copy(
                        name = importedName,
                        wavFilePath = destinationPath,
                        duration = result.durationMs,
                        channelMode = result.channelMode,
                        isImported = true,
                    )
                ProjectAudioImportOutcome.ReadyToPersist(importedTrack)
            }

            is AudioImportResult.Failure ->
                ProjectAudioImportOutcome.ImportRejected(result)
        }
    }
}
