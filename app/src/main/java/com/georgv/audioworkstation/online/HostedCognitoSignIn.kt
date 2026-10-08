package com.georgv.audioworkstation.online

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpTransport
import com.georgv.audioworkstation.online.network.UrlConnectionHttpTransport
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HostedCognitoSignIn(
    private val context: Context,
    private val redirects: CognitoRedirects,
    private val transport: HttpTransport,
    private val io: CoroutineContext,
    private val main: CoroutineContext,
) : CognitoSignIn {
    override suspend fun signIn(): AccountSession {
        val verifier = CognitoOAuth.verifier()
        val oauthState = CognitoOAuth.verifier()
        open(CognitoOAuth.authorizationUrl(CognitoOAuth.codeChallenge(verifier), oauthState))
        val redirect = waitFor(oauthState)
        val code = CognitoOAuth.codeFromQuery(redirect.query.orEmpty(), oauthState)
        return withContext(io) { exchange(CognitoOAuth.tokenForm(code, verifier)) }
    }

    override suspend fun signOut(refreshToken: String) {
        withContext(io) {
            val result = post("/oauth2/revoke", CognitoOAuth.revokeForm(refreshToken))
            if (result.status !in SUCCESS) {
                throw CognitoSignInException(result.body.ifBlank { "sign-out failed" })
            }
        }
    }

    private suspend fun open(url: String) {
        withContext(main) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun waitFor(oauthState: String): Uri {
        val deadline = System.currentTimeMillis() + WAIT_MS
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) throw CognitoSignInException("sign-in timed out")
            val redirect = withTimeoutOrNull(remaining) { redirects.next() }
                ?: throw CognitoSignInException("sign-in timed out")
            if (redirect.getQueryParameter("state") == oauthState) return redirect
        }
    }

    private fun exchange(form: String): AccountSession {
        val result = post("/oauth2/token", form)
        if (result.status !in SUCCESS) {
            throw CognitoSignInException(result.body.ifBlank { "sign-in token exchange failed" })
        }
        return CognitoOAuth.sessionFromTokens(result.body)
    }

    private fun post(path: String, form: String) = transport.exchange(
        HttpCall(
            method = "POST",
            path = path,
            token = null,
            contentType = "application/x-www-form-urlencoded",
            contentLength = form.toByteArray(Charsets.UTF_8).size.toLong(),
            writeBody = { stream -> stream.write(form.toByteArray(Charsets.UTF_8)) },
        ),
    )

    companion object {
        fun create(
            context: Context,
            redirects: CognitoRedirects,
            io: CoroutineContext,
            main: CoroutineContext,
        ): HostedCognitoSignIn = HostedCognitoSignIn(
            context = context,
            redirects = redirects,
            transport = UrlConnectionHttpTransport(CognitoConfig.HOST),
            io = io,
            main = main,
        )

        private val SUCCESS = 200..299
        private const val WAIT_MS = 180_000L
    }
}
