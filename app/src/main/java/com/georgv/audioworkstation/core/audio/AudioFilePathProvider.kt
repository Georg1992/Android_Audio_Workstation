package com.georgv.audioworkstation.core.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface AudioFilePathProvider {
    fun trackOutputPath(projectId: String, trackId: String): String?
}

@Singleton
class DefaultAudioFilePathProvider @Inject constructor(
    @ApplicationContext context: Context
) : AudioFilePathProvider {
    private val appContext = context.applicationContext

    override fun trackOutputPath(projectId: String, trackId: String): String? {
        val projectDir = File(appContext.filesDir, "audio/projects/$projectId")
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            return null
        }
        return File(projectDir, "$trackId.wav").absolutePath
    }
}
