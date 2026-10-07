package com.georgv.audioworkstation.server

data class RegisteredAccount(val accountId: String, val email: String)

data class AccountSession(val token: String, val accountId: String, val email: String)

data class SharedProjectRecord(val id: String, val ownerAccountId: String, val title: String)

data class SharedFileRecord(
    val clientTrackId: String,
    val contentHash: String,
    val storageKey: String,
    val size: Long,
)

data class SharedProjectDetails(
    val id: String,
    val ownerAccountId: String,
    val title: String,
    val files: List<SharedFileRecord>,
)
