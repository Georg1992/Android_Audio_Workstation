package com.georgv.audioworkstation.online

import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

internal object CognitoOAuth {
    fun verifier(): String {
        val bytes = ByteArray(VERIFIER_BYTES)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    fun codeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    fun authorizationUrl(challenge: String, oauthState: String): String {
        val query = form(
            "client_id" to CognitoConfig.CLIENT_ID,
            "redirect_uri" to CognitoConfig.REDIRECT_URI,
            "response_type" to "code",
            "scope" to "openid email",
            "code_challenge" to challenge,
            "code_challenge_method" to "S256",
            "state" to oauthState,
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

    fun revokeForm(refreshToken: String): String =
        form(
            "token" to refreshToken,
            "client_id" to CognitoConfig.CLIENT_ID,
        )

    fun codeFromQuery(query: String, expectedState: String): String {
        val params = queryPairs(query)
        rejectError(params)
        return authorizationCode(params, expectedState)
    }

    fun sessionFromTokens(json: String): AccountSession =
        try {
            readSession(json)
        } catch (error: CognitoSignInException) {
            throw error
        } catch (error: Exception) {
            throw CognitoSignInException("sign-in did not return tokens", error)
        }

    private fun readSession(json: String): AccountSession {
        val body = JSONObject(json)
        val accessToken = body.optString("access_token")
        val refreshToken = body.optString("refresh_token")
        val idToken = body.optString("id_token")
        if (accessToken.isBlank() || refreshToken.isBlank() || idToken.isBlank()) {
            throw CognitoSignInException("sign-in did not return tokens")
        }
        val claims = JSONObject(String(decode(idToken.split('.')[PAYLOAD]), Charsets.UTF_8))
        val subject = claims.optString("sub")
        val email = claims.optString("email")
        if (subject.isBlank() || email.isBlank() || !verified(claims.opt("email_verified"))) {
            throw CognitoSignInException("sign-in token has no verified email")
        }
        return AccountSession(
            token = accessToken,
            accountId = subject,
            email = email,
            refreshToken = refreshToken,
        )
    }

    private fun rejectError(params: Map<String, String>) {
        val error = params["error"] ?: return
        if (error == "access_denied") throw CognitoSignInCancelled()
        throw CognitoSignInException(error)
    }

    private fun authorizationCode(params: Map<String, String>, expectedState: String): String {
        if (params["state"] != expectedState) throw CognitoSignInException("sign-in state did not match")
        val code = params["code"]
        if (code.isNullOrBlank()) throw CognitoSignInException("sign-in did not return a code")
        return code
    }

    private fun verified(value: Any?): Boolean = value == true || value == "true"

    private const val VERIFIER_BYTES = 32
    private const val PAYLOAD = 1
}

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

private fun decode(part: String): ByteArray {
    val padding = (4 - part.length % 4) % 4
    return Base64.getUrlDecoder().decode(part + "=".repeat(padding))
}

private fun encodeQuery(value: String): String = URLEncoder.encode(value, "UTF-8")

private fun urlDecode(value: String): String = URLDecoder.decode(value, "UTF-8")
