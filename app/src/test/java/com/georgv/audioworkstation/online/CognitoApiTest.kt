package com.georgv.audioworkstation.online

import java.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitoApiTest {
    @Test
    fun signUpSendsTheEmailAsTheUsername() {
        val body = JSONObject(CognitoApi.signUp("ada@example.com", "Secret123"))
        assertEquals("15je0c9k5gv0vjfek5v1fmcdf6", body.getString("ClientId"))
        assertEquals("ada@example.com", body.getString("Username"))
        assertEquals("Secret123", body.getString("Password"))
        val attribute = body.getJSONArray("UserAttributes").getJSONObject(0)
        assertEquals("email", attribute.getString("Name"))
        assertEquals("ada@example.com", attribute.getString("Value"))
    }

    @Test
    fun signInUsesThePasswordFlow() {
        val body = JSONObject(CognitoApi.signIn("ada@example.com", "Secret123"))
        assertEquals("USER_PASSWORD_AUTH", body.getString("AuthFlow"))
        assertEquals("ada@example.com", body.getJSONObject("AuthParameters").getString("USERNAME"))
    }

    @Test
    fun authenticationResultBecomesTheSession() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject()
                .put("sub", "sub-1")
                .put("email", "ada@example.com")
                .put("email_verified", true)
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
        val session = CognitoApi.session(
            JSONObject()
                .put(
                    "AuthenticationResult",
                    JSONObject()
                        .put("AccessToken", "access")
                        .put("RefreshToken", "refresh")
                        .put("IdToken", "header.$payload.sig"),
                )
                .toString(),
        )
        assertEquals("access", session.token)
        assertEquals("sub-1", session.accountId)
        assertEquals("ada@example.com", session.email)
        assertEquals("refresh", session.refreshToken)
    }

    @Test
    fun gmailSessionTrustsTheGoogleIdentity() {
        val session = CognitoApi.accountFromTokens(
            "access",
            "refresh",
            idToken(gmailClaims()),
        )
        assertEquals("self@gmail.com", session.email)
    }

    @Test
    fun passwordSessionRejectsAnUnverifiedEmail() {
        val error = runCatching {
            CognitoApi.accountFromTokens("access", "refresh", idToken(unverifiedClaims()))
        }.exceptionOrNull()
        assertTrue(error is CognitoRejected)
    }

    @Test
    fun cognitoErrorTypeDropsTheServicePrefix() {
        val rejected = CognitoApi.rejected(
            """{"__type":"com.amazonaws.cognito.NotAuthorizedException","message":"Incorrect username or password."}""",
        )
        assertEquals("NotAuthorizedException", rejected.type)
        assertEquals("Incorrect username or password.", rejected.message)
    }

    @Test
    fun passwordRequiresLengthCaseAndADigit() {
        assertFalse(acceptablePassword("short1A"))
        assertFalse(acceptablePassword("lowercase1"))
        assertFalse(acceptablePassword("UPPERCASE1"))
        assertFalse(acceptablePassword("NoDigitsHere"))
        assertTrue(acceptablePassword("Secret123"))
        assertTrue(acceptableEmail("ada@example.com"))
        assertFalse(acceptableEmail("ada"))
    }
}

private fun gmailClaims(): JSONObject =
    JSONObject()
        .put("sub", "sub-google")
        .put("email", "self@gmail.com")
        .put("email_verified", false)
        .put(
            "identities",
            JSONArray().put(JSONObject().put("providerName", "Google")),
        )

private fun unverifiedClaims(): JSONObject =
    JSONObject()
        .put("sub", "sub-1")
        .put("email", "ada@example.com")
        .put("email_verified", false)

private fun idToken(claims: JSONObject): String {
    val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
        claims.toString().toByteArray(Charsets.UTF_8),
    )
    return "header.$payload.sig"
}
