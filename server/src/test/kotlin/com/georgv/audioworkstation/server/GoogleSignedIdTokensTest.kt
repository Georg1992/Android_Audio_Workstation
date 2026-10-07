package com.georgv.audioworkstation.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

class GoogleSignedIdTokensTest {
    @Test
    fun verifiesAnRs256TokenFromEitherGoogleIssuer() {
        val keys = rsa()
        val now = 1_700_000_000_000L
        val https = token(keys.private, "k1", claims("https://accounts.google.com", now))
        val plain = token(keys.private, "k1", claims("accounts.google.com", now))
        val google = GoogleSignedIdTokens("web-client", FixedCerts("k1", keys.public)) { now }
        assertEquals("sub-1", google.verify(https).subject)
        assertEquals("ada@gmail.com", google.verify(https).email)
        assertEquals("sub-1", google.verify(plain).subject)
    }

    @Test
    fun rejectsABadSignatureAudienceExpiryUnverifiedEmailAndUnknownAlgorithm() {
        val keys = rsa()
        val other = rsa()
        val now = 1_700_000_000_000L
        val google = GoogleSignedIdTokens("web-client", FixedCerts("k1", keys.public)) { now }
        assertFailure(google, token(other.private, "k1", claims("https://accounts.google.com", now)))
        assertFailure(google, token(keys.private, "k1", claims("https://accounts.google.com", now, audience = "other")))
        assertFailure(google, token(keys.private, "k1", claims("https://accounts.google.com", now - 7_200_000)))
        assertFailure(
            google,
            token(keys.private, "k1", claims("https://accounts.google.com", now, verified = false)),
        )
        assertFailure(google, token(keys.private, "k1", claims("https://accounts.google.com", now), algorithm = "none"))
        assertFailure(google, token(keys.private, "missing", claims("https://accounts.google.com", now)))
    }

    @Test
    fun blankAudienceDoesNotFetchCertificates() {
        val google = GoogleSignedIdTokens("", RejectingCerts())
        val error = runCatching { google.verify("a.b.c") }.exceptionOrNull()
        assertTrue(error is OnlineFailure)
        val failure = error as OnlineFailure
        assertEquals(401, failure.status)
        assertEquals("gmail sign-in is not configured", failure.message)
    }

    private fun assertFailure(google: GoogleSignedIdTokens, idToken: String) {
        val error = runCatching { google.verify(idToken) }.exceptionOrNull()
        assertTrue(error is OnlineFailure)
        assertEquals(401, (error as OnlineFailure).status)
    }

    private fun claims(
        issuer: String,
        nowMillis: Long,
        audience: String = "web-client",
        verified: Boolean = true,
    ): String {
        val exp = nowMillis / 1000 + 3600
        return """
            {"iss":"$issuer","aud":"$audience","exp":$exp,"email":"ada@gmail.com","email_verified":$verified,"sub":"sub-1"}
        """.trimIndent()
    }

    private fun token(privateKey: PrivateKey, keyId: String, payload: String, algorithm: String = "RS256"): String {
        val headerJson = """{"alg":"$algorithm","kid":"$keyId","typ":"JWT"}"""
        val header = base64(headerJson)
        val body = base64(payload)
        val input = "$header.$body"
        val signer = Signature.getInstance("SHA256withRSA")
        signer.initSign(privateKey)
        signer.update(input.toByteArray(Charsets.US_ASCII))
        return "$input.${base64(signer.sign())}"
    }

    private fun base64(text: String): String = base64(text.toByteArray(Charsets.UTF_8))

    private fun base64(bytes: ByteArray): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

    private fun rsa(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()

    private class FixedCerts(private val id: String, private val publicKey: PublicKey) : GoogleCerts {
        override fun key(keyId: String): PublicKey? = if (keyId == id) publicKey else null
    }

    private class RejectingCerts : GoogleCerts {
        override fun key(keyId: String): PublicKey? = error("certs must not be fetched")
    }
}
