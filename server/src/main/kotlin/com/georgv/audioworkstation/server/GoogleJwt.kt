package com.georgv.audioworkstation.server

import org.json.JSONException
import org.json.JSONObject
import java.security.GeneralSecurityException
import java.security.PublicKey
import java.security.Signature
import java.util.Base64

internal class GoogleJwt private constructor(
    val algorithm: String,
    val keyId: String,
    private val signingInput: ByteArray,
    private val signature: ByteArray,
    private val claims: JSONObject,
) {
    fun signatureMatches(key: PublicKey): Boolean =
        try {
            val verifier = Signature.getInstance("SHA256withRSA")
            verifier.initVerify(key)
            verifier.update(signingInput)
            verifier.verify(signature)
        } catch (error: GeneralSecurityException) {
            throw OnlineFailure(401, INVALID, error)
        }

    fun identity(audience: String, nowMillis: Long): GoogleIdentity {
        val issuer = claims.optString("iss")
        if (issuer != ISSUER && issuer != ISSUER_HTTPS) throw OnlineFailure(401, INVALID)
        val aud = claims.opt("aud")
        if (aud !is String || aud != audience) throw OnlineFailure(401, INVALID)
        val expiresAt = claims.optLong("exp", 0L)
        if (expiresAt <= 0L || nowMillis >= expiresAt * MILLIS_PER_SECOND) throw OnlineFailure(401, INVALID)
        if (!claims.optBoolean("email_verified", false)) throw OnlineFailure(401, INVALID)
        val subject = claims.optString("sub")
        val email = claims.optString("email")
        if (subject.isBlank() || email.isBlank()) throw OnlineFailure(401, INVALID)
        return GoogleIdentity(subject = subject, email = email)
    }

    companion object {
        const val INVALID = "gmail token is invalid"
        private const val ISSUER = "accounts.google.com"
        private const val ISSUER_HTTPS = "https://accounts.google.com"
        private const val MILLIS_PER_SECOND = 1000L
        private const val JWT_PARTS = 3

        fun parse(token: String): GoogleJwt =
            try {
                parsed(token)
            } catch (error: JSONException) {
                throw OnlineFailure(401, INVALID, error)
            } catch (error: IllegalArgumentException) {
                throw OnlineFailure(401, INVALID, error)
            }

        private fun parsed(token: String): GoogleJwt {
            val parts = token.split('.')
            if (parts.size != JWT_PARTS || parts.any { it.isEmpty() }) throw OnlineFailure(401, INVALID)
            val header = JSONObject(String(decode(parts[0]), Charsets.UTF_8))
            val keyId = header.optString("kid")
            if (keyId.isBlank()) throw OnlineFailure(401, INVALID)
            return GoogleJwt(
                algorithm = header.optString("alg"),
                keyId = keyId,
                signingInput = "${parts[0]}.${parts[1]}".toByteArray(Charsets.US_ASCII),
                signature = decode(parts[2]),
                claims = JSONObject(String(decode(parts[1]), Charsets.UTF_8)),
            )
        }

        private fun decode(part: String): ByteArray {
            val padding = (4 - part.length % 4) % 4
            return Base64.getUrlDecoder().decode(part + "=".repeat(padding))
        }
    }
}
