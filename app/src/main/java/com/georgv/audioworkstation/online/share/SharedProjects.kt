package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.accessTokenExpired
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpTransport
import org.json.JSONObject

data class SharedProject(
    val id: String,
    val title: String,
)

interface SharedProjects {
    suspend fun list(): List<SharedProject>
}

internal class HttpSharedProjects(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val api: HttpTransport,
) : SharedProjects {
    override suspend fun list(): List<SharedProject> {
        val session = openSession()
        val result = api.exchange(
            HttpCall(
                method = "GET",
                path = "/shared-projects",
                token = session.token,
                contentType = null,
                contentLength = 0,
                writeBody = null,
            ),
        )
        if (result.status !in 200..299) error("share api status ${result.status}")
        return sharedProjects(result.body)
    }

    private suspend fun openSession(): AccountSession {
        val session = sessions.current() ?: error("Sign in to see shared projects")
        if (!accessTokenExpired(session.token, System.currentTimeMillis() / 1000)) return session
        val refreshed = accounts.refresh(session)
        sessions.save(refreshed)
        return refreshed
    }
}

internal fun sharedProjects(body: String): List<SharedProject> {
    val rows = JSONObject(body).getJSONArray("projects")
    val projects = ArrayList<SharedProject>(rows.length())
    for (index in 0 until rows.length()) {
        val row = rows.getJSONObject(index)
        projects += SharedProject(id = row.getString("id"), title = row.getString("title"))
    }
    return projects
}
