package com.georgv.audioworkstation.online.share

interface ProjectShare {
    suspend fun share(projectId: String)

    suspend fun restoreMissing(projectId: String)
}
