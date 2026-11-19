package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.share.OwnedProjectPublish
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.SharedProjects
import com.georgv.audioworkstation.ui.playback.MixSource
import com.georgv.audioworkstation.ui.playback.UrlMixSource
import javax.inject.Inject
import javax.inject.Singleton

interface LocalProjectLookup {
    suspend fun exists(projectId: String): Boolean

    suspend fun present(projectId: String, title: String)
}

interface OwnedShareRefresh {
    suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String>
}

@Singleton
class RoomLocalProjects @Inject constructor(
    private val projects: ProjectRepository,
    private val share: OwnedProjectPublish,
    private val sharedProjects: SharedProjects,
) : LocalProjectLookup, OwnedShareRefresh {
    internal var headerSource: MixSource = UrlMixSource()

    override suspend fun exists(projectId: String): Boolean = projects.projectExists(projectId)

    override suspend fun present(projectId: String, title: String) {
        SharedProjectPlace(projects, sharedProjects, headerSource).place(projectId, title)
    }

    override suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String> {
        val removed = LinkedHashSet<String>()
        for (project in projects) {
            if (this.projects.projectExists(project.id)) continue
            if (share.unpublish(project.id)) removed += project.id
        }
        return removed
    }
}
