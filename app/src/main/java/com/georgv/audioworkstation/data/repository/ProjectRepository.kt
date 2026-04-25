package com.georgv.audioworkstation.data.repository

import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao,
    private val fileStore: ProjectFileStore
) {

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    fun observeProject(projectId: String): Flow<ProjectEntity?> = dao.observeProject(projectId)

    suspend fun projectExists(projectId: String): Boolean = dao.projectExists(projectId)

    suspend fun upsertProject(project: ProjectEntity) = dao.upsertProject(project)

    fun observeTracks(projectId: String): Flow<List<TrackEntity>> = dao.observeTracks(projectId)

    suspend fun upsertTrack(track: TrackEntity) = dao.upsertTrack(track)

    suspend fun upsertTracks(tracks: List<TrackEntity>) = dao.upsertTracks(tracks)

    suspend fun updateTracks(tracks: List<TrackEntity>) = dao.updateTracks(tracks)

    /**
     * Creates the project row if it does not exist yet, then returns the latest version of it.
     * Replaces the legacy "show error and return null" pattern in the ViewModels.
     */
    suspend fun ensureProject(projectId: String, defaultName: String): ProjectEntity {
        if (!dao.projectExists(projectId)) {
            dao.upsertProject(ProjectEntity(id = projectId, name = defaultName))
        }
        return dao.observeProject(projectId).first()
            ?: error("Project $projectId disappeared right after upsert.")
    }

    /**
     * Deletes the project row (cascading track rows via the FK) and the on-disk audio folder.
     * The DAO row is deleted first so the UI sees the project go away immediately even if file
     * cleanup is slow or fails (in which case the orphaned files are harmless).
     */
    suspend fun deleteProject(projectId: String) {
        dao.deleteProject(projectId)
        fileStore.deleteProjectFolder(projectId)
    }

    /**
     * Deletes a single track: removes its audio file, then either removes it from the DB
     * outright (if the project will have no tracks left) or removes it and updates the
     * remaining tracks' positions in a single transaction.
     *
     * The audio file is deleted first so a successful DAO update never leaves an orphan file
     * behind; if the DAO call fails the (already-deleted) file is the price of consistency
     * and the rollback in the ViewModel re-emits the original list.
     */
    suspend fun deleteTrack(track: TrackEntity, remainingTracks: List<TrackEntity>) {
        fileStore.deleteTrackFile(track)
        if (remainingTracks.isEmpty()) {
            dao.deleteTrack(track.id)
        } else {
            dao.deleteTrackAndUpdatePositions(track.id, remainingTracks)
        }
    }

    /**
     * Allocates a fresh [TrackEntity] for the given project at the next available position.
     * The caller is responsible for filling in the audio path / metadata before persisting via
     * [upsertTracks].
     */
    suspend fun appendTrackToProject(projectId: String, name: String): TrackEntity {
        val existing = dao.observeTracks(projectId).first()
        return TrackEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            position = existing.size,
            name = name,
            wavFilePath = ""
        )
    }
}
