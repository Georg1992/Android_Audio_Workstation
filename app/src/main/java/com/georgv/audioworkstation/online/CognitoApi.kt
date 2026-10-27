package com.georgv.audioworkstation.online

import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object CognitoApi {
    fun signUp(email: String, password: String, name: String): String =
        JSONObject()
            .put("ClientId", CognitoConfig.CLIENT_ID)
            .put("Username", email)
            .put("Password", password)
            .put(
                "UserAttributes",
                JSONArray().put(attribute("email", email)).put(attribute("name", name)),
            )
            .toString()

    fun confirm(email: String, code: String): String =
        JSONObject()
            .put("ClientId", CognitoConfig.CLIENT_ID)
            .put("Username", email)
            .put("ConfirmationCode", code)
            .toString()

    fun signIn(email: String, password: String): String =
        JSONObject()
            .put("AuthFlow", "USER_PASSWORD_AUTH")
            .put("ClientId", CognitoConfig.CLIENT_ID)
            .put(
                "AuthParameters",
                JSONObject().put("USERNAME", email).put("PASSWORD", password),
            )
            .toString()

    fun refresh(refreshToken: String): String =
        JSONObject()
            .put("AuthFlow", "REFRESH_TOKEN_AUTH")
            .put("ClientId", CognitoConfig.CLIENT_ID)
            .put("AuthParameters", JSONObject().put("REFRESH_TOKEN", refreshToken))
            .toString()

    fun revoke(refreshToken: String): String =
        JSONObject()
            .put("ClientId", CognitoConfig.CLIENT_ID)
            .put("Token", refreshToken)
            .toString()

    fun session(json: String): AccountSession {
        val auth = authenticationResult(json)
        return accountFromTokens(
            auth.optString("AccessToken"),
            auth.optString("RefreshToken"),
            auth.optString("IdToken"),
        )
    }

    /** Cognito omits the refresh token when an existing one is exchanged. */
    fun refreshed(current: AccountSession, json: String): AccountSession {
        val auth = authenticationResult(json)
        val refreshToken = auth.optString("RefreshToken").ifBlank { current.refreshToken }
        return accountFromTokens(auth.optString("AccessToken"), refreshToken, auth.optString("IdToken"))
    }

    private fun authenticationResult(json: String): JSONObject =
        JSONObject(json).optJSONObject("AuthenticationResult")
            ?: throw CognitoRejected("Unknown", "sign-in did not return tokens")

    fun accountFromTokens(accessToken: String, refreshToken: String, idToken: String): AccountSession {
        if (accessToken.isBlank() || refreshToken.isBlank() || idToken.isBlank()) {
            throw CognitoRejected("Unknown", "sign-in did not return tokens")
        }
        return sessionFromClaims(accessToken, refreshToken, idToken)
    }

    fun rejected(body: String): CognitoRejected {
        val json = jsonOrEmpty(body)
        val type = cognitoType(json.optString("__type"))
        val message = json.optString("message").ifBlank { type }
        return CognitoRejected(type, message)
    }

    private fun sessionFromClaims(accessToken: String, refreshToken: String, idToken: String): AccountSession {
        val claims = JSONObject(String(decode(idToken.split('.')[PAYLOAD]), Charsets.UTF_8))
        val subject = claims.optString("sub")
        val email = claims.optString("email")
        if (subject.isBlank() || email.isBlank() || !trustedEmail(claims)) {
            throw CognitoRejected("Unknown", "sign-in token has no verified email")
        }
        val requested = if (googleAccount(claims)) "" else claims.optString("name")
        return AccountSession(
            token = accessToken,
            accountId = subject,
            email = email,
            name = profileName(requested, email),
            refreshToken = refreshToken,
        )
    }

    private const val PAYLOAD = 1
}

private fun trustedEmail(claims: JSONObject): Boolean =
    when {
        googleAccount(claims) -> true
        else -> verified(claims.opt("email_verified"))
    }

private fun googleAccount(claims: JSONObject): Boolean {
    val identities = claims.optJSONArray("identities") ?: return false
    for (index in 0 until identities.length()) {
        val provider = identities.optJSONObject(index)?.optString("providerName")
        if (provider == "Google") return true
    }
    return false
}

private fun verified(value: Any?): Boolean = value == true || value == "true"

/** True when the access token's exp is within a minute of now. A token with no exp is not treated as expired. */
internal fun accessTokenExpired(token: String, nowEpochSeconds: Long): Boolean {
    val exp = jwtExpiry(token) ?: return false
    return exp <= nowEpochSeconds + ExpiryMarginSeconds
}

private fun jwtExpiry(token: String): Long? {
    val payload = token.split('.').getOrNull(1) ?: return null
    val text = try {
        String(decode(payload), Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val claims = try {
        JSONObject(text)
    } catch (_: JSONException) {
        return null
    }
    if (!claims.has("exp")) return null
    val exp = claims.optLong("exp", -1)
    if (exp < 0L) return null
    return exp
}

private const val ExpiryMarginSeconds = 60L

class CognitoRejected(val type: String, message: String) : Exception(message)

private fun attribute(name: String, value: String): JSONObject =
    JSONObject().put("Name", name).put("Value", value)

private fun jsonOrEmpty(body: String): JSONObject =
    try {
        JSONObject(body)
    } catch (_: JSONException) {
        JSONObject()
    }

private fun cognitoType(raw: String): String {
    val name = raw.substringAfterLast('.').substringBefore('#')
    return if (name.isBlank()) "Unknown" else name
}

private fun decode(part: String): ByteArray {
    val padding = (4 - part.length % 4) % 4
    return Base64.getUrlDecoder().decode(part + "=".repeat(padding))
}
