package com.georgv.audioworkstation.core.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioFilePathProvider @Inject constructor(
    @ApplicationContext context: Context
) {
    private val appContext = context.applicationContext

    fun recordingOutputPath(projectId: String, trackId: String): String? {
        val projectDir = File(appContext.filesDir, "audio/projects/$projectId")
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            return null
        }
        return File(projectDir, "$trackId.wav").absolutePath
    }
}
