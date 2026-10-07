package com.georgv.audioworkstation.online.network

import com.georgv.audioworkstation.online.ProjectShareApi
import com.georgv.audioworkstation.online.SharedFileCommit
import com.georgv.audioworkstation.online.SharedProject
import com.georgv.audioworkstation.online.StorageKeyRequest
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpProjectShareApi(
    private val http: JsonHttp,
    private val io: CoroutineDispatcher,
) : ProjectShareApi {
    override suspend fun createSharedProject(token: String, title: String): SharedProject =
        withContext(io) {
            val json = http.post("/projects", token, JSONObject().put("title", title))
            SharedProject(
                id = json.requiredText("id"),
                ownerAccountId = json.requiredText("ownerAccountId"),
                title = json.requiredText("title"),
            )
        }

    override suspend fun requestStorageKey(token: String, projectId: String, request: StorageKeyRequest): String =
        withContext(io) {
            val body = JSONObject()
                .put("clientTrackId", request.clientTrackId)
                .put("contentHash", request.contentHash)
                .put("size", request.size)
            http.post("/projects/$projectId/files/storage-key", token, body).requiredText("storageKey")
        }

    override suspend fun writeStoredFile(token: String, storageKey: String, file: File) {
        withContext(io) { http.putFile("/storage/$storageKey", token, file) }
    }

    override suspend fun commitStoredFile(token: String, projectId: String, file: SharedFileCommit) {
        withContext(io) {
            val body = JSONObject()
                .put("clientTrackId", file.clientTrackId)
                .put("contentHash", file.contentHash)
                .put("storageKey", file.storageKey)
                .put("size", file.size)
            http.post("/projects/$projectId/files", token, body)
        }
    }
}
