package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.ProjectRecordingCoordinator
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
}

internal fun testProjectRecordingCoordinator(
    repo: ProjectRepository,
    audio: AudioController,
    paths: AudioFilePathProvider = TempDirAudioFilePathProvider(),
): ProjectRecordingCoordinator =
    ProjectRecordingCoordinator(
        repo = repo,
        audioController = audio,
        audioFilePathProvider = paths,
        wavPunchSplicer = WavPunchSplicer(),
    )
