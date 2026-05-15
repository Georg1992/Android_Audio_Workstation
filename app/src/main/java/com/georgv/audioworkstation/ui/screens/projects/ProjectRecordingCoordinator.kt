package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.toRecordingSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import javax.inject.Inject
import kotlin.math.max

/** Start path for a new take; the ViewModel applies Flows, DB upsert, and rollback on failures. */
sealed class RecordingStartOutcome {

    /** [AudioController.startRecording] returned null — caller shows [com.georgv.audioworkstation.R.string.error_recording_failed_to_start]. */
    data object EngineStartFailed : RecordingStartOutcome()

    data class ReadyToPersistRecordingRow(val newTrack: TrackEntity) : RecordingStartOutcome()
}

/**
 * Deterministic recording helpers: allocate a pending track, start the native recorder, build the
 * optimistic row. No [MutableStateFlow], no user messages, no DB writes.
 */
class ProjectRecordingCoordinator @Inject constructor(
    private val repo: ProjectRepository,
    private val audioController: AudioController,
) {

    /**
     * Reserve the next logical take row without starting the recorder (fast path for optimistic UI).
     */
    suspend fun allocatePendingRecordingTrack(projectId: String, visibleTrackCount: Int): TrackEntity =
        repo.appendTrackToProject(
            projectId = projectId,
            name = "Take ${visibleTrackCount + 1}",
        )

    /**
     * Start native capture for a row already published to the recording session flows.
     * On success returns the row enriched with output path + recording timestamps.
     */
    suspend fun startEngineForAllocatedTrack(project: ProjectEntity, pendingTrack: TrackEntity): RecordingStartOutcome {
        val outputPath =
            audioController.startRecording(project.toRecordingSpec(pendingTrack))
                ?: return RecordingStartOutcome.EngineStartFailed
        val newTrack =
            pendingTrack.copy(
                wavFilePath = outputPath,
                timeStampStart = System.currentTimeMillis(),
                isRecording = true,
            )
        return RecordingStartOutcome.ReadyToPersistRecordingRow(newTrack)
    }

    suspend fun beginRecording(
        projectId: String,
        project: ProjectEntity,
        visibleTrackCount: Int,
    ): RecordingStartOutcome {
        val pendingTrack = allocatePendingRecordingTrack(projectId, visibleTrackCount)
        return startEngineForAllocatedTrack(project, pendingTrack)
    }

    /**
     * Row to persist after a successful [AudioController.stopRecording], matching prior
     * [ProjectViewModel] finalize math.
     */
    fun finalizedTrackAfterStop(currentTrack: TrackEntity): TrackEntity {
        val stopTimestamp = System.currentTimeMillis()
        val duration = max(0L, stopTimestamp - currentTrack.timeStampStart)
        return currentTrack.copy(
            timeStampStop = stopTimestamp,
            duration = duration,
            isRecording = false,
        )
    }
}
