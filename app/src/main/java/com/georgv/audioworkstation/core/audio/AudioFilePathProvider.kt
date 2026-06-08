package com.georgv.audioworkstation.core.audio

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface AudioFilePathProvider {
    fun projectRecordingDirectory(projectId: String): String?

    fun trackOutputPath(projectId: String, trackId: String): String?

    /** Temporary capture file for punch recording; must not overwrite the live track WAV. */
    fun trackRecordingTempPath(projectId: String, trackId: String): String?

    /** Offline mixdown output for Library preview and export. */
    fun mixdownOutputPath(projectId: String): String?
}

@Singleton
class DefaultAudioFilePathProvider @Inject constructor(
    @ApplicationContext context: Context
) : AudioFilePathProvider {
    private val appContext = context.applicationContext

    override fun projectRecordingDirectory(projectId: String): String? {
        val projectDir = File(appContext.filesDir, "audio/projects/$projectId")
        if (!projectDir.exists() && !projectDir.mkdirs()) {
            return null
        }
        return projectDir.absolutePath
    }

    override fun trackOutputPath(projectId: String, trackId: String): String? {
        val projectDir = projectRecordingDirectory(projectId) ?: return null
        return File(projectDir, "$trackId.wav").absolutePath
    }

    override fun trackRecordingTempPath(projectId: String, trackId: String): String? {
        val projectDir = projectRecordingDirectory(projectId) ?: return null
        return File(projectDir, "$trackId.recording.tmp.wav").absolutePath
    }

    override fun mixdownOutputPath(projectId: String): String? {
        val projectDir = projectRecordingDirectory(projectId) ?: return null
        return File(projectDir, "mixdown.wav").absolutePath
    }
}
