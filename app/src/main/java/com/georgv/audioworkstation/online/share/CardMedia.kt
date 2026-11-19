package com.georgv.audioworkstation.online.share

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface CardMedia {
    suspend fun mixUrl(projectId: String): String

    suspend fun image(projectId: String, hash: String): File
}

internal class HttpCardMedia(
    private val projects: SharedProjects,
    private val cache: CardCache,
    private val objects: ShareObjectTransport,
) : CardMedia {
    private val sessionMixes = HashMap<String, String>()

    override suspend fun mixUrl(projectId: String): String = withContext(Dispatchers.IO) {
        storedMix(projectId)?.let { return@withContext it }
        val first = projects.playback(projectId).firstOrNull() ?: error("shared project has no audio")
        val dest = cache.mixFile(first.contentHash)
        if (!dest.isFile) objects.download(first.downloadUrl, dest)
        rememberMix(projectId, dest.absolutePath)
    }

    override suspend fun image(projectId: String, hash: String): File = withContext(Dispatchers.IO) {
        val dest = cache.imageFile(hash)
        if (!dest.isFile) objects.download(projects.imageUrl(projectId), dest)
        dest
    }

    private fun storedMix(projectId: String): String? = synchronized(sessionMixes) { sessionMixes[projectId] }

    private fun rememberMix(projectId: String, path: String): String {
        synchronized(sessionMixes) { sessionMixes[projectId] = path }
        return path
    }
}
