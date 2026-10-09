package com.georgv.audioworkstation.online

data class AccountSession(
    val token: String,
    val accountId: String,
    val email: String,
    val name: String,
    val refreshToken: String,
)
