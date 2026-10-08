package com.georgv.audioworkstation.online

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.UrlConnectionHttpTransport
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import org.json.JSONObject

class LoopbackGmailSignIn(
    private val context: Context,
    private val clientId: String,
    private val clientSecret: String,
    private val io: CoroutineDispatcher,
    private val main: CoroutineDispatcher,
) : GmailSignIn {
    override suspend fun idToken(): String {
        if (clientId.isBlank() || clientSecret.isBlank()) throw GmailSignInNotConfigured()
        val verifier = GmailOAuth.verifier()
        val state = GmailOAuth.verifier()
        val challenge = GmailOAuth.codeChallenge(verifier)
        return withContext(io) {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(LOOPBACK, 0))
                server.soTimeout = SIGN_IN_TIMEOUT_MS
                val redirect = "http://$LOOPBACK:${server.localPort}/"
                val url = GmailOAuth.authorizationUrl(clientId, redirect, challenge, state)
                withContext(main) { openBrowser(url) }
                val code = awaitCode(server, state)
                exchange(code, verifier, redirect)
            }
        }
    }

    private fun openBrowser(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun awaitCode(server: ServerSocket, state: String): String {
        val client = server.accept()
        client.soTimeout = SIGN_IN_TIMEOUT_MS
        return client.use { socket ->
            val request = socket.getInputStream().bufferedReader(Charsets.UTF_8).readLine()
                ?: throw GmailSignInException("gmail sign-in did not return a code")
            val code = GmailOAuth.codeFromRedirect(request, state)
            val body = "Signed in. Return to the app."
            val bytes = body.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/plain; charset=utf-8\r\n" +
                "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n$body"
            socket.getOutputStream().write(response.toByteArray(Charsets.UTF_8))
            code
        }
    }

    private fun exchange(code: String, verifier: String, redirect: String): String {
        val body = GmailOAuth.tokenForm(clientId, clientSecret, code, verifier, redirect)
        val bytes = body.toByteArray(Charsets.UTF_8)
        val result = UrlConnectionHttpTransport(TOKEN_ENDPOINT).exchange(
            HttpCall(
                method = "POST",
                path = "/token",
                token = null,
                contentType = "application/x-www-form-urlencoded",
                contentLength = bytes.size.toLong(),
                writeBody = { it.write(bytes) },
            ),
        )
        if (result.status !in 200..299) {
            throw GmailSignInException(result.body.ifBlank { "gmail token exchange failed" })
        }
        val idToken = JSONObject(result.body).optString("id_token")
        if (idToken.isBlank()) throw GmailSignInException("gmail sign-in did not return a token")
        return idToken
    }

    private companion object {
        const val LOOPBACK = "127.0.0.1"
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com"
        const val SIGN_IN_TIMEOUT_MS = 180_000
    }
}
