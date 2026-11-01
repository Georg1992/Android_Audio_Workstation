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
}
