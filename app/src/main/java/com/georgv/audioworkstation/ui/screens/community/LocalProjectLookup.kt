package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.share.ProjectShare
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface LocalProjectLookup {
    suspend fun exists(projectId: String): Boolean

    suspend fun publishOwnerNames()
}

@Singleton
class RoomLocalProjects @Inject constructor(
    private val projects: ProjectRepository,
    private val sessions: AccountSessionStore,
    private val share: ProjectShare,
) : LocalProjectLookup {
    override suspend fun exists(projectId: String): Boolean = projects.projectExists(projectId)

    override suspend fun publishOwnerNames() {
        val session = sessions.current() ?: return
        val owned = projects.observeProjects().first().filter { project ->
            project.syncStatus == SyncStatus.SYNCED && project.ownerUserId == session.accountId
        }
        for (project in owned) {
            share.publishOwner(project.id)
        }
    }
}
