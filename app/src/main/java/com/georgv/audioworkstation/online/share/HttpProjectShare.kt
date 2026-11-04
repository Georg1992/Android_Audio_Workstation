package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpTransport
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class HttpProjectShare(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val projects: ProjectRepository,
    private val api: HttpTransport,
    private val objects: ShareObjectTransport,
    private val paths: AudioFilePathProvider,
) : ProjectShare, SharedCardUpdate, SharedTakeRestore, OwnedProjectPublish {
    override suspend fun share(projectId: String) {
        val session = openSession("Sign in to share a project")
        val project = projects.observeProject(projectId).first() ?: error("Project $projectId is not on this phone")
        val mix = mixFile(project.id)
        val files = tracksToShare(projects.observeTracks(project.id).first())
        val hash = sha256Hex(mix)
        var published = false
        val failure = runCatching {
            storeObject(session, hash, mix)
            post(
                session,
                "/shared-projects",
                JSONObject()
                    .put("id", project.id)
                    .put("title", project.name.orEmpty())
                    .put("ownerName", session.name)
                    .put("contentHash", hash)
                    .put("size", mix.length())
                    .toString(),
            )
            published = true
            projects.upsertProject(project.copy(syncStatus = SyncStatus.SYNCING, ownerUserId = session.accountId))
            shareFiles(session, project, files)
        }.exceptionOrNull()
        if (failure == null) return
        var listed = false
        var listUnread = false
        try {
            val rows = sharedProjects(api.exchangeAuthorized(session, "GET", "/shared-projects", null))
            listed = session.name.isNotEmpty() && rows.any { row ->
                row.id == project.id && row.ownerName == session.name
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logWarning(TAG, "community list was not read after share", error)
            listUnread = true
        }
        if (listed || listUnread && published) {
            projects.upsertProject(project.copy(syncStatus = SyncStatus.SYNCED, ownerUserId = session.accountId))
            return
        }
        if (published && unpublish(project.id)) {
            projects.upsertProject(project.copy(syncStatus = SyncStatus.LOCAL, ownerUserId = null))
        }
        throw failure
    }

    private suspend fun shareFiles(
        session: AccountSession,
        project: ProjectEntity,
        files: List<SharedTrackFile>,
    ) {
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
        val tracks = projects.observeTracks(project.id).first()
        projects.updateTracks(
            tracks.map { track ->
                val hash = hashes[track.id] ?: return@map track
                track.copy(contentHash = hash, syncStatus = SyncStatus.SYNCED)
            },
        )
        publishCard(session, project.id, project.name.orEmpty(), project.cardTags, project.cardImagePath)
        projects.upsertProject(
            project.copy(syncStatus = SyncStatus.SYNCED, ownerUserId = session.accountId),
        )
    }

    override suspend fun unshare(projectId: String) {
        val session = openSession("Sign in to stop sharing a project")
        val project = projects.observeProject(projectId).first() ?: error("Project $projectId is not on this phone")
        val result = api.exchange(
            HttpCall(
                method = "DELETE",
                path = "/shared-projects/${project.id}",
                token = session.token,
                contentType = null,
                contentLength = 0L,
                writeBody = null,
            ),
        )
        val removed = result.status in HttpOk || result.status == NotFound
        if (!removed) error("share api status ${result.status}")
        projects.upsertProject(project.copy(syncStatus = SyncStatus.LOCAL, ownerUserId = null))
    }

    override suspend fun updateCard(projectId: String, title: String, tags: String, imagePath: String?) {
        publishCard(openSession("Sign in to update the card"), projectId, title, tags, imagePath)
    }

    override suspend fun publishOwner(projectId: String) {
        withContext(Dispatchers.IO) {
            val session = openSession("Sign in to share a project")
            post(
                session,
                "/shared-projects/$projectId/owner",
                JSONObject().put("name", session.name).toString(),
            )
        }
    }

    override suspend fun publishMix(projectId: String) {
        withContext(Dispatchers.IO) {
            uploadHashed(openSession("Sign in to share a project"), projectId, "mix", mixFile(projectId))
        }
    }

    override suspend fun unpublish(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val session = openSession("Sign in to stop sharing a project")
        val result = api.exchange(
            HttpCall(
                method = "DELETE",
                path = "/shared-projects/$projectId",
                token = session.token,
                contentType = null,
                contentLength = 0L,
                writeBody = null,
            ),
        )
        when (result.status) {
            NotOwner -> false
            NotFound -> true
            in HttpOk -> true
            else -> error("share api status ${result.status}")
        }
    }

    override suspend fun restoreMissing(projectId: String) {
        val missing = tracksToRestore(projects.observeTracks(projectId).first())
        if (missing.isEmpty()) return
        val session = openSession("Sign in to download a shared take")
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

    private suspend fun openSession(signedOut: String): AccountSession =
        openShareSession(sessions, accounts, signedOut)

    private fun post(session: AccountSession, path: String, json: String): JSONObject =
        JSONObject(api.exchangeAuthorized(session, "POST", path, json))

    private fun publishCard(
        session: AccountSession,
        projectId: String,
        title: String,
        tags: String,
        imagePath: String?,
    ) {
        val tagJson = JSONArray()
        checkedCardTags(tags).forEach { tag -> tagJson.put(tag) }
        post(
            session,
            "/shared-projects/$projectId/card",
            JSONObject().put("title", title).put("tags", tagJson).put("ownerName", session.name).toString(),
        )
        val path = imagePath ?: return
        val file = File(path)
        if (!file.isFile) error("card image is missing")
        uploadHashed(session, projectId, "image", file)
    }

    private fun mixFile(projectId: String): File {
        val path = paths.mixdownOutputPath(projectId) ?: error("shared project has no mix")
        val file = File(path)
        if (!file.isFile) error("shared project has no mix")
        return file
    }

    private fun storeObject(session: AccountSession, hash: String, file: File) {
        val upload = post(
            session,
            "/shared-objects",
            JSONObject().put("contentHash", hash).put("size", file.length()).toString(),
        )
        if (upload.getBoolean("alreadyStored")) return
        val url = upload.getString("uploadUrl")
        if (url.isBlank()) error("share api did not return an upload url")
        objects.put(url, headers(upload), file)
    }

    private fun uploadHashed(session: AccountSession, projectId: String, kind: String, file: File) {
        val hash = sha256Hex(file)
        val upload = post(
            session,
            "/shared-projects/$projectId/$kind",
            JSONObject().put("contentHash", hash).put("size", file.length()).toString(),
        )
        if (!upload.getBoolean("alreadyStored")) {
            val url = upload.getString("uploadUrl")
            if (url.isBlank()) error("share api did not return an upload url")
            objects.put(url, headers(upload), file)
        }
        post(
            session,
            "/shared-projects/$projectId/$kind/commit",
            JSONObject()
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

    private companion object {
        const val TAG = "HttpProjectShare"
        const val NotOwner = 403
        const val NotFound = 404
        val HttpOk = 200..299
    }
}
