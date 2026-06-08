package com.georgv.audioworkstation.core.audio

import java.io.File

internal class TempDirAudioFilePathProvider(
    rootDir: File = File.createTempFile("aaw-audio-paths", "").apply { delete(); mkdirs() },
) : AudioFilePathProvider {
    private val root = rootDir

    override fun projectRecordingDirectory(projectId: String): String =
        File(root, projectId).apply { mkdirs() }.absolutePath

    override fun trackOutputPath(projectId: String, trackId: String): String =
        File(projectRecordingDirectory(projectId), "$trackId.wav").absolutePath

    override fun trackRecordingTempPath(projectId: String, trackId: String): String =
        File(projectRecordingDirectory(projectId), "$trackId.recording.tmp.wav").absolutePath

    override fun mixdownOutputPath(projectId: String): String =
        File(projectRecordingDirectory(projectId), "mixdown.wav").absolutePath
}
