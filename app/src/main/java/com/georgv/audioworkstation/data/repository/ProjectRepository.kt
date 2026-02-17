package com.georgv.audioworkstation.data.repository

import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.db.relations.ProjectWithTracks
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProjectRepository @Inject constructor(
    private val dao: ProjectDao
) {

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    fun observeTracks(projectId: String): Flow<List<TrackEntity>> = dao.observeTracks(projectId)

    suspend fun getProjectWithTracks(projectId: String): ProjectWithTracks? =
        dao.getProjectWithTracks(projectId)

    suspend fun upsertProject(project: ProjectEntity) = dao.upsertProject(project)

    suspend fun upsertTrack(track: TrackEntity) = dao.upsertTrack(track)

    suspend fun upsertTracks(tracks: List<TrackEntity>) = dao.upsertTracks(tracks)

    suspend fun deleteProject(projectId: String) = dao.deleteProject(projectId)
}
