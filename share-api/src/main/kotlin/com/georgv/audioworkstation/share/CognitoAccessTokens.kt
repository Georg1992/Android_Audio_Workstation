package com.georgv.audioworkstation.share

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jwt.SignedJWT
import java.net.URL
import java.util.Date

internal class CognitoAccessTokens(
    private val jwksUrl: String = JWKS,
) {
    private var keys = load()

    fun accountId(token: String): String {
        val jwt = parse(token)
        val key = keyFor(jwt) ?: throw ShareFailure(401, "unauthorized")
        if (!jwt.verify(RSASSAVerifier(key))) throw ShareFailure(401, "unauthorized")
        val claims = jwt.jwtClaimsSet
        val expired = claims.expirationTime == null || !claims.expirationTime.after(Date())
        val wrongIssuer = claims.issuer != ISSUER
        val wrongUse = claims.getStringClaim("token_use") != "access"
        val wrongClient = claims.getStringClaim("client_id") != CLIENT_ID
        if (expired || wrongIssuer || wrongUse || wrongClient) throw ShareFailure(401, "unauthorized")
        val subject = claims.subject
        if (subject.isNullOrBlank()) throw ShareFailure(401, "unauthorized")
        return subject
    }

    private fun keyFor(jwt: SignedJWT) = keys.getKeyByKeyId(jwt.header.keyID)?.toRSAKey()
        ?: load().also { keys = it }.getKeyByKeyId(jwt.header.keyID)?.toRSAKey()

    private fun parse(token: String): SignedJWT = try {
        val jwt = SignedJWT.parse(token)
        if (jwt.header.algorithm != JWSAlgorithm.RS256) throw ShareFailure(401, "unauthorized")
        jwt
    } catch (failure: ShareFailure) {
        throw failure
    } catch (error: Exception) {
        throw ShareFailure(401, "unauthorized")
    }

    private fun load(): JWKSet = JWKSet.parse(URL(jwksUrl).readText())

    private companion object {
        const val ISSUER = "https://cognito-idp.us-east-1.amazonaws.com/us-east-1_CaWxPoiz1"
        const val CLIENT_ID = "15je0c9k5gv0vjfek5v1fmcdf6"
        const val JWKS = "$ISSUER/.well-known/jwks.json"
    }
}
