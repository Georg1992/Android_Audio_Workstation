package com.georgv.audioworkstation.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleAuthTest {
    @Test
    fun codeChallengeMatchesThePkceVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleAuth.codeChallenge(verifier))
    }

    @Test
    fun authorizationUrlOpensGoogleThroughCognito() {
        val url = GoogleAuth.authorizationUrl(challenge = "challenge", oauthState = "state-1")
        assertTrue(url.startsWith("https://georg-audioworkstation.auth.us-east-1.amazoncognito.com/oauth2/authorize?"))
        assertTrue(url.contains("identity_provider=Google"))
        assertTrue(url.contains("client_id=15je0c9k5gv0vjfek5v1fmcdf6"))
        assertTrue(url.contains("redirect_uri=audioworkstation%3A%2F%2Fcallback"))
        assertTrue(url.contains("prompt=select_account"))
        assertTrue(!url.contains("client_secret"))
    }

    @Test
    fun queryReturnsTheCodeWhenTheStateMatches() {
        assertEquals("auth-code", GoogleAuth.codeFromQuery("state=state-1&code=auth-code", "state-1"))
    }

    @Test
    fun queryTreatsAccessDeniedAsCancelled() {
        val denied = runCatching { GoogleAuth.codeFromQuery("error=access_denied&state=state-1", "state-1") }
            .exceptionOrNull()
        assertTrue(denied is CognitoSignInCancelled)
    }
}
