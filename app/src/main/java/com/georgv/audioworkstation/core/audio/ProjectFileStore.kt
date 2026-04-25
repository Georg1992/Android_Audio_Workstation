package com.georgv.audioworkstation.core.audio

import android.content.Context
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the on-disk audio files associated with a project.
 *
 * Kept separate from [AudioFilePathProvider] (which only computes paths for fresh recordings or
 * imports) because deletion is a different concern: it crosses the "domain owns the lifecycle"
 * boundary and is invoked from the repository alongside DAO writes.
 */
interface ProjectFileStore {
    suspend fun deleteTrackFile(track: TrackEntity)
    suspend fun deleteProjectFolder(projectId: String)
}

@Singleton
class DefaultProjectFileStore @Inject constructor(
    @ApplicationContext context: Context
) : ProjectFileStore {

    private val appContext = context.applicationContext

    override suspend fun deleteTrackFile(track: TrackEntity) = withContext(Dispatchers.IO) {
        val path = track.wavFilePath
        if (path.isBlank()) return@withContext
        val file = File(path)
        if (file.exists()) {
            file.delete()
        }
        Unit
    }

    override suspend fun deleteProjectFolder(projectId: String) = withContext(Dispatchers.IO) {
        val projectDir = File(appContext.filesDir, "audio/projects/$projectId")
        if (projectDir.exists()) {
            projectDir.deleteRecursively()
        }
        Unit
    }
}
