package com.georgv.audioworkstation.online

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class HttpOnlineApi(
    private val baseUrl: String,
    private val io: CoroutineDispatcher,
) : OnlineApi {
    override suspend fun createAccount(email: String, password: String): AccountSession =
        withContext(io) {
            val json = postJson("/accounts", null, credentials(email, password))
            accountSession(json)
        }

    override suspend fun createSession(email: String, password: String): AccountSession =
        withContext(io) {
            val json = postJson("/sessions", null, credentials(email, password))
            accountSession(json)
        }

    override suspend fun deleteSession(token: String) {
        withContext(io) {
            val connection = open("/sessions", "DELETE", token)
            try {
                readJson(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun createSharedProject(token: String, title: String): SharedProject =
        withContext(io) {
            val json = postJson("/projects", token, JSONObject().put("title", title))
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
            val json = postJson("/projects/$projectId/files/storage-key", token, body)
            json.requiredText("storageKey")
        }

    override suspend fun writeStoredFile(token: String, storageKey: String, file: File) {
        withContext(io) {
            val connection = open("/storage/$storageKey", "PUT", token)
            try {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/octet-stream")
                connection.setFixedLengthStreamingMode(file.length())
                file.inputStream().use { input ->
                    connection.outputStream.use { output -> input.copyTo(output) }
                }
                readJson(connection)
            } finally {
                connection.disconnect()
            }
        }
    }

    override suspend fun commitStoredFile(token: String, projectId: String, file: SharedFileCommit) {
        withContext(io) {
            val body = JSONObject()
                .put("clientTrackId", file.clientTrackId)
                .put("contentHash", file.contentHash)
                .put("storageKey", file.storageKey)
                .put("size", file.size)
            postJson("/projects/$projectId/files", token, body)
        }
    }

    private fun credentials(email: String, password: String): JSONObject =
        JSONObject().put("email", email).put("password", password)

    private fun postJson(path: String, token: String?, body: JSONObject): JSONObject {
        val connection = open(path, "POST", token)
        try {
            val bytes = body.toString().toByteArray(Charsets.UTF_8)
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            return readJson(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun open(path: String, method: String, token: String?): HttpURLConnection {
        val connection = URL(baseUrl + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.instanceFollowRedirects = false
        connection.useCaches = false
        if (token != null) connection.setRequestProperty("Authorization", "Bearer $token")
        return connection
    }

    private fun readJson(connection: HttpURLConnection): JSONObject {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (status !in 200..299) throw OnlineApiException(status, text.ifBlank { "HTTP $status" })
        if (text.isBlank()) return JSONObject()
        return JSONObject(text)
    }

    private fun accountSession(json: JSONObject): AccountSession =
        AccountSession(
            token = json.requiredText("token"),
            accountId = json.requiredText("accountId"),
            email = json.requiredText("email"),
        )

    private fun JSONObject.requiredText(name: String): String {
        if (!has(name) || isNull(name)) throw OnlineApiException(0, "$name is required")
        val value = getString(name)
        if (value.isBlank()) throw OnlineApiException(0, "$name is required")
        return value
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
    }
}
