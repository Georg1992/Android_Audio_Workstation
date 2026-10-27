package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.accessTokenExpired
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpTransport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

interface SharedProjects {
    suspend fun list(): List<SharedProject>

    suspend fun imageUrl(id: String): String

    suspend fun playback(id: String): List<SharedPlayback>
}

data class SharedProject(
    val id: String,
    val title: String,
    val tags: List<String>,
    val imageHash: String?,
    val ownerName: String,
)

data class SharedPlayback(
    val contentHash: String,
    val downloadUrl: String,
)

internal class HttpSharedProjects(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val api: HttpTransport,
) : SharedProjects {
    override suspend fun list(): List<SharedProject> = withContext(Dispatchers.IO) {
        sharedProjects(call(openSession(), "GET", "/shared-projects", null))
    }

    override suspend fun imageUrl(id: String): String = withContext(Dispatchers.IO) {
        JSONObject(call(openSession(), "GET", "/shared-projects/$id/image", null)).getString("downloadUrl")
    }

    override suspend fun playback(id: String): List<SharedPlayback> = withContext(Dispatchers.IO) {
        val rows = JSONObject(call(openSession(), "POST", "/shared-projects/$id/playback", null)).getJSONArray("files")
        val files = ArrayList<SharedPlayback>(rows.length())
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            files += SharedPlayback(
                contentHash = row.getString("contentHash"),
                downloadUrl = row.getString("downloadUrl"),
            )
        }
        files
    }

    private suspend fun openSession(): AccountSession {
        val session = sessions.current() ?: error("Sign in to see shared projects")
        if (!accessTokenExpired(session.token, System.currentTimeMillis() / 1000)) return session
        val refreshed = accounts.refresh(session)
        sessions.save(refreshed)
        return refreshed
    }

    private fun call(session: AccountSession, method: String, path: String, json: String?): String {
        val bytes = json?.toByteArray(Charsets.UTF_8)
        val result = api.exchange(
            HttpCall(
                method = method,
                path = path,
                token = session.token,
                contentType = if (bytes == null) null else "application/json",
                contentLength = bytes?.size?.toLong() ?: 0L,
                writeBody = if (bytes == null) null else { stream -> stream.write(bytes) },
            ),
        )
        if (result.status !in 200..299) error("share api status ${result.status}")
        return result.body
    }
}

internal fun sharedProjects(body: String): List<SharedProject> {
    val rows = JSONObject(body).getJSONArray("projects")
    val projects = ArrayList<SharedProject>(rows.length())
    for (index in 0 until rows.length()) {
        val row = rows.getJSONObject(index)
        projects += SharedProject(
            id = row.getString("id"),
            title = row.getString("title"),
            tags = jsonTags(row.getJSONArray("tags")),
            imageHash = if (row.isNull("imageHash")) null else row.getString("imageHash"),
            ownerName = row.getString("ownerName"),
        )
    }
    return projects
}

private fun jsonTags(array: JSONArray): List<String> {
    val tags = ArrayList<String>(array.length())
    for (index in 0 until array.length()) tags += array.getString(index)
    return tags
}
