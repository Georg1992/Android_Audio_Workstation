package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.ProjectRecordingCoordinator

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
