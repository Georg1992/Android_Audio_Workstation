package com.georgv.audioworkstation.online

import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal object GoogleAuth {
    fun verifier(): String {
        val bytes = ByteArray(VerifierBytes)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizationUrl(challenge: String, oauthState: String): String {
        val query = form(
            "identity_provider" to "Google",
            "client_id" to CognitoConfig.CLIENT_ID,
            "redirect_uri" to CognitoConfig.REDIRECT_URI,
            "response_type" to "code",
            "scope" to "openid email",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to oauthState,
            "prompt" to "select_account",
        )
        return "${CognitoConfig.HOST}/oauth2/authorize?$query"
    }

    fun tokenForm(code: String, verifier: String): String =
        form(
            "grant_type" to "authorization_code",
            "client_id" to CognitoConfig.CLIENT_ID,
            "code" to code,
            "redirect_uri" to CognitoConfig.REDIRECT_URI,
            "code_verifier" to verifier,
        )

    fun codeFromQuery(query: String, expectedState: String): String {
        val params = queryPairs(query)
        val error = params["error"]
        if (error != null) throw queryError(error)
        return authorizationCode(params, expectedState)
    }

    private fun queryError(error: String): Exception =
        if (error == "access_denied") {
            CognitoSignInCancelled()
        } else {
            CognitoRejected(error, error)
        }

    private fun authorizationCode(params: Map<String, String>, expectedState: String): String {
        if (params["state"] != expectedState) {
            throw CognitoRejected("Unknown", "sign-in state did not match")
        }
        return requiredCode(params["code"])
    }

    private fun requiredCode(code: String?): String {
        if (code.isNullOrBlank()) throw CognitoRejected("Unknown", "sign-in did not return a code")
        return code
    }

    private const val VerifierBytes = 32
}

class CognitoSignInCancelled : Exception("google sign-in was cancelled")

private fun queryPairs(query: String): Map<String, String> {
    if (query.isEmpty()) return emptyMap()
    return query.split('&').mapNotNull { pair ->
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

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun urlDecode(value: String): String = java.net.URLDecoder.decode(value, "UTF-8")
