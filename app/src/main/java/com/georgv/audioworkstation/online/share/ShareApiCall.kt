package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.accessTokenExpired
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpTransport

internal suspend fun openShareSession(
    sessions: AccountSessionStore,
    accounts: CognitoAccounts,
    signedOut: String,
): AccountSession {
    val session = sessions.current() ?: error(signedOut)
    if (!accessTokenExpired(session.token, System.currentTimeMillis() / 1000)) return session
    val refreshed = accounts.refresh(session)
    sessions.save(refreshed)
    return refreshed
}

internal fun HttpTransport.exchangeAuthorized(
    session: AccountSession,
    method: String,
    path: String,
    json: String? = null,
): String {
    val bytes = json?.toByteArray(Charsets.UTF_8)
    val result = exchange(
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
