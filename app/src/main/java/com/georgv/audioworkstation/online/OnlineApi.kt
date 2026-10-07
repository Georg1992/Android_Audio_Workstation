package com.georgv.audioworkstation.online

import java.io.File

const val HttpUnauthorized = 401

data class AccountSession(val token: String, val accountId: String, val email: String)

data class SharedProject(val id: String, val ownerAccountId: String, val title: String)

data class StorageKeyRequest(val clientTrackId: String, val contentHash: String, val size: Long)

data class SharedFileCommit(
    val clientTrackId: String,
    val contentHash: String,
    val storageKey: String,
    val size: Long,
)

class OnlineApiException(val status: Int, message: String) : Exception(message)

class ShareException(message: String) : Exception(message)

interface OnlineApi {
    suspend fun createAccount(email: String, password: String): AccountSession

    suspend fun createSession(email: String, password: String): AccountSession

    suspend fun deleteSession(token: String)

    suspend fun createSharedProject(token: String, title: String): SharedProject

    suspend fun requestStorageKey(token: String, projectId: String, request: StorageKeyRequest): String

    suspend fun writeStoredFile(token: String, storageKey: String, file: File)

    suspend fun commitStoredFile(token: String, projectId: String, file: SharedFileCommit)
}
