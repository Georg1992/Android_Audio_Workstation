package com.georgv.audioworkstation.server

import java.security.PublicKey

data class GoogleIdentity(val subject: String, val email: String)

interface GoogleIdTokens {
    fun verify(idToken: String): GoogleIdentity
}

interface GoogleCerts {
    fun key(keyId: String): PublicKey?
}
