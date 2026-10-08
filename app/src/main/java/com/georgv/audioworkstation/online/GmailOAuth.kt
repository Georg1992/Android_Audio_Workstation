package com.georgv.audioworkstation.online

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object GmailOAuth {
    fun verifier(): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizationUrl(clientId: String, redirectUri: String, challenge: String, state: String): String {
        val query = form(
            "client_id" to clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to state,
            "prompt" to "select_account",
        )
        return "https://accounts.google.com/o/oauth2/v2/auth?$query"
    }

    fun tokenForm(clientId: String, clientSecret: String, code: String, verifier: String, redirectUri: String): String =
        form(
            "code" to code,
            "client_id" to clientId,
            "client_secret" to clientSecret,
            "redirect_uri" to redirectUri,
            "grant_type" to "authorization_code",
            "code_verifier" to verifier,
        )

    fun codeFromRedirect(requestLine: String, expectedState: String): String {
        val params = query(requestLine)
        rejectGoogleError(params)
        rejectState(params, expectedState)
        return authorizationCode(params)
    }

    private fun rejectGoogleError(params: Map<String, String>) {
        val error = params["error"] ?: return
        throw GmailSignInException(error)
    }

    private fun rejectState(params: Map<String, String>, expectedState: String) {
        if (params["state"] != expectedState) throw GmailSignInException("gmail sign-in state did not match")
    }

    private fun authorizationCode(params: Map<String, String>): String {
        val code = params["code"]
        if (code.isNullOrBlank()) throw GmailSignInException("gmail sign-in did not return a code")
        return code
    }

    private fun query(requestLine: String): Map<String, String> {
        val target = requestLine.substringAfter(' ').substringBefore(' ')
        val raw = target.substringAfter('?', "")
        if (raw.isEmpty()) return emptyMap()
        return raw.split('&').mapNotNull { pair ->
            val name = urlDecode(pair.substringBefore('='))
            if (name.isEmpty()) {
                null
            } else {
                name to urlDecode(pair.substringAfter('=', ""))
            }
        }.toMap()
    }

    private fun form(vararg fields: Pair<String, String>): String =
        fields.joinToString("&") { (name, value) -> "$name=${encodeQuery(value)}" }

    private const val VERIFIER_BYTES = 32
}

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")
