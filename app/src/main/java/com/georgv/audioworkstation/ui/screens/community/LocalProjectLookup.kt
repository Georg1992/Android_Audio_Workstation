package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.data.repository.ProjectRepository
import javax.inject.Inject
import javax.inject.Singleton

interface LocalProjectLookup {
    suspend fun exists(projectId: String): Boolean
}

@Singleton
class RoomLocalProjects @Inject constructor(
    private val projects: ProjectRepository,
) : LocalProjectLookup {
    override suspend fun exists(projectId: String): Boolean = projects.projectExists(projectId)
}
