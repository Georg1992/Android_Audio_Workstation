package com.georgv.audioworkstation.online

import java.util.Base64
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

internal object CognitoApi {
    fun signUp(email: String, password: String): String =
        JSONObject()
            .put("ClientId", CognitoConfig.CLIENT_ID)
            .put("Username", email)
            .put("Password", password)
            .put("UserAttributes", JSONArray().put(attribute("email", email)))
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

    fun revoke(refreshToken: String): String =
        JSONObject()
            .put("ClientId", CognitoConfig.CLIENT_ID)
            .put("Token", refreshToken)
            .toString()

    fun session(json: String): AccountSession {
        val auth = JSONObject(json).optJSONObject("AuthenticationResult")
            ?: throw CognitoRejected("Unknown", "sign-in did not return tokens")
        return accountFromTokens(
            auth.optString("AccessToken"),
            auth.optString("RefreshToken"),
            auth.optString("IdToken"),
        )
    }

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
        if (subject.isBlank() || email.isBlank() || !verified(claims.opt("email_verified"))) {
            throw CognitoRejected("Unknown", "sign-in token has no verified email")
        }
        return AccountSession(
            token = accessToken,
            accountId = subject,
            email = email,
            refreshToken = refreshToken,
        )
    }

    private fun verified(value: Any?): Boolean = value == true || value == "true"

    private const val PAYLOAD = 1
}

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
