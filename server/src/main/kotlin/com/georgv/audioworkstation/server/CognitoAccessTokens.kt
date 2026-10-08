package com.georgv.audioworkstation.server

import java.security.PublicKey

data class CognitoIdentity(val subject: String, val email: String)

interface CognitoAccessTokens {
    fun verify(token: String): CognitoIdentity
}

interface CognitoJwks {
    fun key(keyId: String): PublicKey?
}
