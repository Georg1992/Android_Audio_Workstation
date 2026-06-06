package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.ProjectRecordingCoordinator

internal fun testProjectRecordingCoordinator(
    repo: ProjectRepository,
    audio: AudioController,
    paths: AudioFilePathProvider = TempDirAudioFilePathProvider(),
    dispatchers: AppDispatchers = TestAppDispatchers(),
): ProjectRecordingCoordinator =
    ProjectRecordingCoordinator(
        repo = repo,
        audioController = audio,
        audioFilePathProvider = paths,
        wavPunchSplicer = WavPunchSplicer(),
        dispatchers = dispatchers,
    )
