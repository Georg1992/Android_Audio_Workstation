package com.georgv.audioworkstation.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GmailOAuthTest {
    @Test
    fun codeChallengeMatchesThePkceVector() {
        val verifier = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GmailOAuth.codeChallenge(verifier))
    }

    @Test
    fun authorizationUrlCarriesTheDesktopClientAndLoopbackRedirect() {
        val url = GmailOAuth.authorizationUrl(
            clientId = "desktop.apps.googleusercontent.com",
            redirectUri = "http://127.0.0.1:9/",
            challenge = "challenge",
            state = "state-1",
        )
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("client_id=desktop.apps.googleusercontent.com"))
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A9%2F"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("scope=openid+email"))
    }

    @Test
    fun redirectRequestReturnsTheCodeWhenTheStateMatches() {
        val line = "GET /?state=state-1&code=auth-code HTTP/1.1"
        assertEquals("auth-code", GmailOAuth.codeFromRedirect(line, "state-1"))
    }

    @Test
    fun redirectRequestRejectsAGoogleErrorAndADifferentState() {
        val denied = runCatching {
            GmailOAuth.codeFromRedirect("GET /?error=access_denied&state=state-1 HTTP/1.1", "state-1")
        }.exceptionOrNull()
        assertTrue(denied is GmailSignInException)
        assertEquals("access_denied", denied?.message)

        val mismatch = runCatching {
            GmailOAuth.codeFromRedirect("GET /?code=auth-code&state=other HTTP/1.1", "state-1")
        }.exceptionOrNull()
        assertTrue(mismatch is GmailSignInException)
    }
}
