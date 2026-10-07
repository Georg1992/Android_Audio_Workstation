package com.georgv.audioworkstation.online

import java.io.File

data class SharedProject(val id: String, val ownerAccountId: String, val title: String)

data class StorageKeyRequest(val clientTrackId: String, val contentHash: String, val size: Long)

data class SharedFileCommit(
    val clientTrackId: String,
    val contentHash: String,
    val storageKey: String,
    val size: Long,
)

interface ProjectShareApi {
    suspend fun createSharedProject(token: String, title: String): SharedProject

    suspend fun requestStorageKey(token: String, projectId: String, request: StorageKeyRequest): String

    suspend fun writeStoredFile(token: String, storageKey: String, file: File)

    suspend fun commitStoredFile(token: String, projectId: String, file: SharedFileCommit)
}
