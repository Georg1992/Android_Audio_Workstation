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
import org.json.JSONObject

class GoogleCognitoSignIn(
    private val context: Context,
    private val redirects: CognitoRedirects,
    private val transport: HttpTransport,
    private val io: CoroutineContext,
    private val main: CoroutineContext,
) {
    suspend fun signIn(): AccountSession {
        val verifier = GoogleAuth.verifier()
        val oauthState = GoogleAuth.verifier()
        open(GoogleAuth.authorizationUrl(GoogleAuth.codeChallenge(verifier), oauthState))
        val redirect = waitFor(oauthState)
        val code = GoogleAuth.codeFromQuery(redirect.query.orEmpty(), oauthState)
        return withContext(io) { exchange(GoogleAuth.tokenForm(code, verifier)) }
    }

    private suspend fun open(url: String) {
        withContext(main) {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun waitFor(oauthState: String): Uri {
        val deadline = System.currentTimeMillis() + WaitMs
        while (true) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0L) throw CognitoRejected("Unknown", "sign-in timed out")
            val redirect = withTimeoutOrNull(remaining) { redirects.next() }
                ?: throw CognitoRejected("Unknown", "sign-in timed out")
            if (redirect.getQueryParameter("state") == oauthState) return redirect
        }
    }

    private fun exchange(form: String): AccountSession {
        val bytes = form.toByteArray(Charsets.UTF_8)
        val result = transport.exchange(
            HttpCall(
                method = "POST",
                path = "/oauth2/token",
                token = null,
                contentType = "application/x-www-form-urlencoded",
                contentLength = bytes.size.toLong(),
                writeBody = { stream -> stream.write(bytes) },
            ),
        )
        if (result.status !in Success) {
            throw IllegalStateException(result.body.ifBlank { "sign-in failed" })
        }
        val body = JSONObject(result.body)
        return CognitoApi.accountFromTokens(
            body.optString("access_token"),
            body.optString("refresh_token"),
            body.optString("id_token"),
        )
    }

    companion object {
        fun create(
            context: Context,
            redirects: CognitoRedirects,
            io: CoroutineContext,
            main: CoroutineContext,
        ): GoogleCognitoSignIn = GoogleCognitoSignIn(
            context = context,
            redirects = redirects,
            transport = UrlConnectionHttpTransport(CognitoConfig.HOST),
            io = io,
            main = main,
        )

        private val Success = 200..299
        private const val WaitMs = 180_000L
    }
}
