package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.network.HttpTransport
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

interface SharedTakePublish {
    suspend fun publishNewTake(track: TrackEntity): TrackEntity
}

internal class HttpSharedTakePublish(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val projects: ProjectRepository,
    private val api: HttpTransport,
    private val objects: ShareObjectTransport,
) : SharedTakePublish {
    override suspend fun publishNewTake(track: TrackEntity): TrackEntity = withContext(Dispatchers.IO) {
        val project = projects.observeProject(track.projectId).first() ?: return@withContext track
        if (!isShared(project)) return@withContext track
        val file = recordedFile(track)
        val hash = sha256Hex(file)
        if (track.contentHash == hash) return@withContext track
        upload(project.id, track.id, hash, file)
        track.copy(contentHash = hash)
    }

    private fun recordedFile(track: TrackEntity): File {
        val file = File(track.wavFilePath)
        if (!file.isFile) error("recorded take is missing")
        return file
    }

    private suspend fun upload(projectId: String, trackId: String, hash: String, file: File) {
        val session = openShareSession(sessions, accounts, "Sign in to share a project")
        val upload = JSONObject(
            api.exchangeAuthorized(
                session,
                "POST",
                "/shared-projects/$projectId/uploads",
                JSONObject()
                    .put("trackId", trackId)
                    .put("contentHash", hash)
                    .put("size", file.length())
                    .toString(),
            ),
        )
        if (!upload.getBoolean("alreadyStored")) {
            val url = upload.optString("uploadUrl")
            if (url.isBlank()) error("share api did not return an upload url")
            objects.put(url, headers(upload), file)
        }
        api.exchangeAuthorized(
            session,
            "POST",
            "/shared-projects/$projectId/files",
            JSONObject()
                .put("trackId", trackId)
                .put("contentHash", hash)
                .put("storageKey", hash)
                .put("size", file.length())
                .toString(),
        )
    }

    private fun headers(upload: JSONObject): Map<String, String> {
        val json = upload.optJSONObject("headers") ?: error("share api did not return upload headers")
        val headers = LinkedHashMap<String, String>()
        for (name in json.keys()) {
            headers[name] = json.getString(name)
        }
        return headers
    }

    private fun isShared(project: ProjectEntity): Boolean {
        if (project.syncStatus == SyncStatus.SYNCED) return true
        return !project.remoteUrl.isNullOrBlank()
    }
}
