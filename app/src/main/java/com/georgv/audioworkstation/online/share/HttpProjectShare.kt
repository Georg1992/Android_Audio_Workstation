package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpTransport
import java.io.File
import kotlinx.coroutines.flow.first
import org.json.JSONObject

internal class HttpProjectShare(
    private val sessions: AccountSessionStore,
    private val projects: ProjectRepository,
    private val api: HttpTransport,
    private val objects: ShareObjectTransport,
) : ProjectShare {
    override suspend fun share(projectId: String) {
        val session = sessions.current() ?: error("Sign in to share a project")
        val project = projects.observeProject(projectId).first() ?: error("Project $projectId is not on this phone")
        val files = tracksToShare(projects.observeTracks(projectId).first())
        post(
            session,
            "/shared-projects",
            JSONObject().put("id", project.id).put("title", project.name.orEmpty()).toString(),
        )
        val hashes = LinkedHashMap<String, String>()
        for (file in files) {
            val hash = sha256Hex(file.file)
            val upload = post(
                session,
                "/shared-projects/${project.id}/uploads",
                JSONObject()
                    .put("trackId", file.trackId)
                    .put("contentHash", hash)
                    .put("size", file.file.length())
                    .toString(),
            )
            if (!upload.getBoolean("alreadyStored")) {
                val url = upload.optString("uploadUrl")
                if (url.isBlank()) error("share api did not return an upload url")
                objects.put(url, headers(upload), file.file)
            }
            post(
                session,
                "/shared-projects/${project.id}/files",
                JSONObject()
                    .put("trackId", file.trackId)
                    .put("contentHash", hash)
                    .put("storageKey", hash)
                    .put("size", file.file.length())
                    .toString(),
            )
            hashes[file.trackId] = hash
        }
        val tracks = projects.observeTracks(projectId).first()
        projects.updateTracks(
            tracks.map { track ->
                val hash = hashes[track.id] ?: return@map track
                track.copy(contentHash = hash, syncStatus = SyncStatus.SYNCED)
            },
        )
        projects.upsertProject(
            project.copy(syncStatus = SyncStatus.SYNCED, ownerUserId = session.accountId),
        )
    }

    override suspend fun restoreMissing(projectId: String) {
        val missing = tracksToRestore(projects.observeTracks(projectId).first())
        if (missing.isEmpty()) return
        val session = sessions.current() ?: error("Sign in to download a shared take")
        for (track in missing) {
            val hash = track.contentHash ?: error("shared take ${track.id} has no content hash")
            val response = post(
                session,
                "/shared-projects/$projectId/downloads",
                JSONObject().put("contentHash", hash).toString(),
            )
            val url = response.optString("downloadUrl")
            if (url.isBlank()) error("share api did not return a download url")
            objects.download(url, File(track.wavFilePath))
        }
    }

    private fun post(session: AccountSession, path: String, json: String): JSONObject {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val result = api.exchange(
            HttpCall(
                method = "POST",
                path = path,
                token = session.token,
                contentType = "application/json",
                contentLength = bytes.size.toLong(),
                writeBody = { it.write(bytes) },
            ),
        )
        if (result.status !in 200..299) error("share api status ${result.status}")
        return JSONObject(result.body)
    }

    private fun headers(upload: JSONObject): Map<String, String> {
        val json = upload.optJSONObject("headers") ?: error("share api did not return upload headers")
        val headers = LinkedHashMap<String, String>()
        for (name in json.keys()) {
            headers[name] = json.getString(name)
        }
        return headers
    }
}
