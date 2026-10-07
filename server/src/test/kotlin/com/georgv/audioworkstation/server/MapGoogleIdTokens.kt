package com.georgv.audioworkstation.server

class MapGoogleIdTokens : GoogleIdTokens {
    private val accepted = linkedMapOf<String, GoogleIdentity>()

    fun accept(token: String, subject: String, email: String) {
        accepted[token] = GoogleIdentity(subject = subject, email = email)
    }

    override fun verify(idToken: String): GoogleIdentity =
        accepted[idToken] ?: throw OnlineFailure(401, "gmail token is invalid")
}
