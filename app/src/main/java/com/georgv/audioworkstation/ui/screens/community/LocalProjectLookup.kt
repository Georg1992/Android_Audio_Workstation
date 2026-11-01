package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.share.OwnedProjectPublish
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface LocalProjectLookup {
    suspend fun exists(projectId: String): Boolean
}

interface OwnedShareRefresh {
    suspend fun publishOwnerNames()

    suspend fun publishMixes()
}

@Singleton
class RoomLocalProjects @Inject constructor(
    private val projects: ProjectRepository,
    private val sessions: AccountSessionStore,
    private val share: OwnedProjectPublish,
    private val paths: AudioFilePathProvider,
) : LocalProjectLookup, OwnedShareRefresh {
    override suspend fun exists(projectId: String): Boolean = projects.projectExists(projectId)

    override suspend fun publishOwnerNames() {
        for (project in ownedSynced()) {
            share.publishOwner(project.id)
        }
    }

    override suspend fun publishMixes() {
        for (project in ownedSynced()) {
            val path = paths.mixdownOutputPath(project.id) ?: continue
            if (!File(path).isFile) continue
            share.publishMix(project.id)
        }
    }

    private suspend fun ownedSynced(): List<ProjectEntity> {
        val session = sessions.current() ?: return emptyList()
        return projects.observeProjects().first().filter { project ->
            project.syncStatus == SyncStatus.SYNCED && project.ownerUserId == session.accountId
        }
    }
}
