package com.georgv.audioworkstation.server

class MapCognitoAccessTokens : CognitoAccessTokens {
    private val identities = mutableMapOf<String, CognitoIdentity>()

    fun accept(token: String, subject: String, email: String) {
        identities[token] = CognitoIdentity(subject = subject, email = email)
    }

    override fun verify(token: String): CognitoIdentity =
        identities[token] ?: throw OnlineFailure(401, "session is not valid")
}
