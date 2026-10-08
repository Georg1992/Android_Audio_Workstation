package com.georgv.audioworkstation.online

import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CognitoOAuthTest {
    @Test
    fun codeChallengeMatchesThePkceVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", CognitoOAuth.codeChallenge(verifier))
    }

    @Test
    fun authorizationUrlUsesThePublicClientAndAppRedirect() {
        val url = CognitoOAuth.authorizationUrl(challenge = "challenge", oauthState = "state-1")
        assertTrue(url.startsWith("https://georg-audioworkstation.auth.us-east-1.amazoncognito.com/oauth2/authorize?"))
        assertTrue(url.contains("client_id=15je0c9k5gv0vjfek5v1fmcdf6"))
        assertTrue(url.contains("redirect_uri=audioworkstation%3A%2F%2Fcallback"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertFalseSecret(url)
    }

    @Test
    fun queryReturnsTheCodeWhenTheStateMatches() {
        assertEquals("auth-code", CognitoOAuth.codeFromQuery("state=state-1&code=auth-code", "state-1"))
    }

    @Test
    fun queryTreatsAccessDeniedAsCancelledAndRejectsADifferentState() {
        val denied = runCatching { CognitoOAuth.codeFromQuery("error=access_denied&state=state-1", "state-1") }
            .exceptionOrNull()
        assertTrue(denied is CognitoSignInCancelled)
        val mismatch = runCatching { CognitoOAuth.codeFromQuery("code=auth-code&state=other", "state-1") }
            .exceptionOrNull()
        assertTrue(mismatch is CognitoSignInException)
    }

    @Test
    fun tokenResponseBecomesTheSession() {
        val payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
            JSONObject()
                .put("sub", "sub-1")
                .put("email", "ada@example.com")
                .put("email_verified", true)
                .toString()
                .toByteArray(Charsets.UTF_8),
        )
        val session = CognitoOAuth.sessionFromTokens(
            JSONObject()
                .put("access_token", "access")
                .put("refresh_token", "refresh")
                .put("id_token", "header.$payload.sig")
                .toString(),
        )
        assertEquals("access", session.token)
        assertEquals("sub-1", session.accountId)
        assertEquals("ada@example.com", session.email)
        assertEquals("refresh", session.refreshToken)
    }

    private fun assertFalseSecret(url: String) {
        assertTrue(!url.contains("client_secret"))
    }
}
