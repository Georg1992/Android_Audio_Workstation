package com.georgv.audioworkstation.online.share

interface ProjectShare {
    suspend fun share(projectId: String)

    suspend fun unshare(projectId: String)

    suspend fun restoreMissing(projectId: String)

    suspend fun updateCard(projectId: String, title: String, tags: String, imagePath: String?)

    suspend fun publishOwner(projectId: String)
}
