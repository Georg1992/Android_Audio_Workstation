package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpTransport
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext

interface CognitoAccounts {
    suspend fun register(email: String, password: String)

    suspend fun confirm(email: String, code: String)

    suspend fun signIn(email: String, password: String): AccountSession

    suspend fun signOut(refreshToken: String)
}

class ApiCognitoAccounts(
    private val transport: HttpTransport,
    private val io: CoroutineContext,
) : CognitoAccounts {
    override suspend fun register(email: String, password: String) {
        withContext(io) { post(SIGN_UP, CognitoApi.signUp(email, password)) }
    }

    override suspend fun confirm(email: String, code: String) {
        withContext(io) { post(CONFIRM, CognitoApi.confirm(email, code)) }
    }

    override suspend fun signIn(email: String, password: String): AccountSession =
        withContext(io) {
            CognitoApi.session(post(SIGN_IN, CognitoApi.signIn(email, password)))
        }

    override suspend fun signOut(refreshToken: String) {
        withContext(io) { post(REVOKE, CognitoApi.revoke(refreshToken)) }
    }

    private fun post(target: String, json: String): String {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val result = transport.exchange(
            HttpCall(
                method = "POST",
                path = "/",
                token = null,
                contentType = CONTENT_TYPE,
                contentLength = bytes.size.toLong(),
                writeBody = { stream -> stream.write(bytes) },
                headers = mapOf(TARGET_HEADER to target),
            ),
        )
        if (result.status !in SUCCESS) throw CognitoApi.rejected(result.body)
        return result.body
    }

    private companion object {
        const val SIGN_UP = "AWSCognitoIdentityProviderService.SignUp"
        const val CONFIRM = "AWSCognitoIdentityProviderService.ConfirmSignUp"
        const val SIGN_IN = "AWSCognitoIdentityProviderService.InitiateAuth"
        const val REVOKE = "AWSCognitoIdentityProviderService.RevokeToken"
        const val CONTENT_TYPE = "application/x-amz-json-1.1"
        const val TARGET_HEADER = "X-Amz-Target"
        val SUCCESS = 200..299
    }
}
