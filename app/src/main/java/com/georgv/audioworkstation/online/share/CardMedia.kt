package com.georgv.audioworkstation.online.share

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CardMedia {
    suspend fun audio(projectId: String): File

    suspend fun image(projectId: String, hash: String): File
}

internal class HttpCardMedia(
    private val projects: SharedProjects,
    private val cache: CardCache,
    private val objects: ShareObjectTransport,
) : CardMedia {
    override suspend fun audio(projectId: String): File = withContext(Dispatchers.IO) {
        val first = projects.playback(projectId).firstOrNull() ?: error("shared project has no audio")
        val dest = cache.audioFile(first.contentHash)
        if (!dest.isFile) objects.download(first.downloadUrl, dest)
        dest
    }

    override suspend fun image(projectId: String, hash: String): File = withContext(Dispatchers.IO) {
        val dest = cache.imageFile(hash)
        if (!dest.isFile) objects.download(projects.imageUrl(projectId), dest)
        dest
    }
}
