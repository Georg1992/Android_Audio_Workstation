package com.georgv.audioworkstation.online.share

interface ProjectShare {
    suspend fun share(projectId: String)

    suspend fun unshare(projectId: String)
}

interface SharedCardUpdate {
    suspend fun updateCard(projectId: String, title: String, tags: String, imagePath: String?)
}

interface SharedTakeRestore {
    suspend fun restoreMissing(projectId: String)
}

interface OwnedProjectPublish {
    suspend fun publishOwner(projectId: String)

    suspend fun publishMix(projectId: String)

    /** Deletes this account's share. Returns false when the account does not own it. */
    suspend fun unpublish(projectId: String): Boolean
}
