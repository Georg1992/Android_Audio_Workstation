package com.georgv.audioworkstation.online.network

import com.georgv.audioworkstation.online.AccountApi
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.RegisteredAccount
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

class HttpAccountApi(
    private val http: JsonHttp,
    private val io: CoroutineDispatcher,
) : AccountApi {
    override suspend fun register(email: String, password: String): RegisteredAccount =
        withContext(io) {
            val json = http.post("/accounts", null, credentials(email, password))
            RegisteredAccount(accountId = json.requiredText("accountId"), email = json.requiredText("email"))
        }

    override suspend fun createSession(email: String, password: String): AccountSession =
        withContext(io) {
            session(http.post("/sessions", null, credentials(email, password)))
        }

    override suspend fun createGoogleSession(idToken: String): AccountSession =
        withContext(io) {
            val body = JSONObject().put("idToken", idToken)
            session(http.post("/sessions/google", null, body))
        }

    override suspend fun deleteSession(token: String) {
        withContext(io) { http.delete("/sessions", token) }
    }

    private fun session(json: JSONObject): AccountSession =
        AccountSession(
            token = json.requiredText("token"),
            accountId = json.requiredText("accountId"),
            email = json.requiredText("email"),
        )

    private fun credentials(email: String, password: String): JSONObject =
        JSONObject().put("email", email).put("password", password)
}
