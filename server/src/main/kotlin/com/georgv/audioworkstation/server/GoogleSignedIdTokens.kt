package com.georgv.audioworkstation.server

class GoogleSignedIdTokens(
    private val audience: String,
    private val certs: GoogleCerts,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : GoogleIdTokens {
    override fun verify(idToken: String): GoogleIdentity {
        if (audience.isBlank()) throw OnlineFailure(401, "gmail sign-in is not configured")
        val jwt = GoogleJwt.parse(idToken)
        if (jwt.algorithm != RS256) throw OnlineFailure(401, GoogleJwt.INVALID)
        val key = certs.key(jwt.keyId) ?: throw OnlineFailure(401, GoogleJwt.INVALID)
        if (!jwt.signatureMatches(key)) throw OnlineFailure(401, GoogleJwt.INVALID)
        return jwt.identity(audience, nowMillis())
    }

    private companion object {
        const val RS256 = "RS256"
    }
}
