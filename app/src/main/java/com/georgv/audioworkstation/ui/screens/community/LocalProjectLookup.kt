package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.share.OwnedProjectPublish
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.sha256Hex
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

interface LocalProjectLookup {
    suspend fun exists(projectId: String): Boolean

    suspend fun present(projectId: String, title: String)
}

interface OwnedShareRefresh {
    suspend fun publishOwnerNames()

    suspend fun publishMixes()

    suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String>
}

@Singleton
class RoomLocalProjects @Inject constructor(
    private val projects: ProjectRepository,
    private val sessions: AccountSessionStore,
    private val share: OwnedProjectPublish,
    private val paths: AudioFilePathProvider,
) : LocalProjectLookup, OwnedShareRefresh {
    private val published = PublishedCardMarks()
    override suspend fun exists(projectId: String): Boolean = projects.projectExists(projectId)

    override suspend fun present(projectId: String, title: String) {
        projects.ensureProject(projectId, title)
    }

    override suspend fun publishOwnerNames() {
        val name = sessions.current()?.name ?: return
        for (project in ownedSynced()) {
            if (published.ownerUnchanged(project.id, name)) continue
            share.publishOwner(project.id)
            published.markOwner(project.id, name)
        }
    }

    override suspend fun publishMixes() {
        for (project in ownedSynced()) {
            val path = paths.mixdownOutputPath(project.id) ?: continue
            val file = File(path)
            if (!file.isFile) continue
            val hash = sha256Hex(file)
            if (published.mixUnchanged(project.id, hash)) continue
            share.publishMix(project.id)
            published.markMix(project.id, hash)
        }
    }

    override suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String> {
        val removed = LinkedHashSet<String>()
        for (project in projects) {
            if (this.projects.projectExists(project.id)) continue
            if (share.unpublish(project.id)) removed += project.id
        }
        return removed
    }

    private suspend fun ownedSynced(): List<ProjectEntity> {
        val session = sessions.current() ?: return emptyList()
        return projects.observeProjects().first().filter { project ->
            project.syncStatus == SyncStatus.SYNCED && project.ownerUserId == session.accountId
        }
    }
}

internal class PublishedCardMarks {
    private val owners = HashMap<String, String>()
    private val mixes = HashMap<String, String>()

    fun ownerUnchanged(projectId: String, name: String): Boolean = owners[projectId] == name

    fun markOwner(projectId: String, name: String) {
        owners[projectId] = name
    }

    fun mixUnchanged(projectId: String, hash: String): Boolean = mixes[projectId] == hash

    fun markMix(projectId: String, hash: String) {
        mixes[projectId] = hash
    }
}
