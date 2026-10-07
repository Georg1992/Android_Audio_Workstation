package com.georgv.audioworkstation.online

data class RegisteredAccount(val accountId: String, val email: String)

data class AccountSession(val token: String, val accountId: String, val email: String)

interface AccountApi {
    suspend fun register(email: String, password: String): RegisteredAccount

    suspend fun createSession(email: String, password: String): AccountSession

    suspend fun createGoogleSession(idToken: String): AccountSession

    suspend fun deleteSession(token: String)
}
