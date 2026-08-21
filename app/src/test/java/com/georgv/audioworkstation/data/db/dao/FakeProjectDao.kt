package com.georgv.audioworkstation.data.db.dao

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

internal class FakeProjectDao(
    projects: List<ProjectEntity> = emptyList(),
    tracks: List<TrackEntity> = emptyList(),
    private val failUpsertProject: Boolean = false,
    private val failUpsertTrack: Boolean = false,
    private val failDeleteTrackAndUpdatePositions: Boolean = false,
    private val updateTracksGate: CompletableDeferred<Unit>? = null
) : ProjectDao {
    private val projectsFlow = MutableStateFlow(projects.sortedByDescending { it.createdAt })
    private val tracksByProject = tracks.groupBy { it.projectId }
        .mapValues { (_, list) -> MutableStateFlow(list.sortedBy { it.position }) }
        .toMutableMap()

    override suspend fun insertProject(project: ProjectEntity) {
        if (failUpsertProject) error("insertProject failed")
        projectsFlow.value = (projectsFlow.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.createdAt }
    }

    override suspend fun updateProject(project: ProjectEntity) {
        if (failUpsertProject) error("updateProject failed")
        projectsFlow.value = (projectsFlow.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.createdAt }
    }

    override fun observeProjects(): Flow<List<ProjectEntity>> = projectsFlow

    override fun observeProject(projectId: String): Flow<ProjectEntity?> =
        projectsFlow.map { projects -> projects.firstOrNull { it.id == projectId } }

    override suspend fun projectExists(projectId: String): Boolean =
        projectsFlow.value.any { it.id == projectId }

    override suspend fun deleteProject(projectId: String) {
        projectsFlow.value = projectsFlow.value.filterNot { it.id == projectId }
        tracksByProject.remove(projectId)
    }

    override fun observeTracks(projectId: String): Flow<List<TrackEntity>> =
        tracksByProject.getOrPut(projectId) { MutableStateFlow(emptyList()) }

    override suspend fun upsertTrack(track: TrackEntity) {
        if (failUpsertTrack) error("upsertTrack failed")
        val flow = tracksByProject.getOrPut(track.projectId) { MutableStateFlow(emptyList()) }
        flow.value = (flow.value.filterNot { it.id == track.id } + track).sortedBy { it.position }
    }

    override suspend fun upsertTracks(tracks: List<TrackEntity>) {
        tracks.forEach { upsertTrack(it) }
    }

    override suspend fun updateTracks(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        updateTracksGate?.await()
        tracksByProject.getOrPut(tracks.first().projectId) { MutableStateFlow(emptyList()) }
            .value = tracks.sortedBy { it.position }
    }

    override suspend fun deleteTrack(trackId: String) {
        tracksByProject.values.forEach { flow ->
            flow.value = flow.value.filterNot { it.id == trackId }
        }
    }

    override suspend fun deleteTrackAndUpdatePositions(trackId: String, remaining: List<TrackEntity>) {
        if (failDeleteTrackAndUpdatePositions) error("deleteTrackAndUpdatePositions failed")
        super.deleteTrackAndUpdatePositions(trackId, remaining)
    }
}
