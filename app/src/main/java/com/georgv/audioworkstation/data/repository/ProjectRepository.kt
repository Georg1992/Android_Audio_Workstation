package com.georgv.audioworkstation.data.repository

import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao
) {

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    fun observeProject(projectId: String): Flow<ProjectEntity?> = dao.observeProject(projectId)

    suspend fun projectExists(projectId: String): Boolean = dao.projectExists(projectId)

    suspend fun deleteProject(projectId: String) = dao.deleteProject(projectId)

    suspend fun upsertProject(project: ProjectEntity) = dao.upsertProject(project)

    fun observeTracks(projectId: String): Flow<List<TrackEntity>> = dao.observeTracks(projectId)

    suspend fun updateTracks(tracks: List<TrackEntity>) = dao.updateTracks(tracks)

    suspend fun upsertTrack(track: TrackEntity) = dao.upsertTrack(track)

    suspend fun upsertTracks(tracks: List<TrackEntity>) = dao.upsertTracks(tracks)

    suspend fun deleteTrack(trackId: String) = dao.deleteTrack(trackId)

    suspend fun deleteTrackAndUpdatePositions(trackId: String, remaining: List<TrackEntity>) =
        dao.deleteTrackAndUpdatePositions(trackId, remaining)
}
