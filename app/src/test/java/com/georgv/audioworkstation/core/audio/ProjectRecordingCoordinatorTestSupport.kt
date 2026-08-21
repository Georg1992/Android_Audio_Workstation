package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.core.session.ProjectRecordingCoordinator

internal fun testProjectRecordingCoordinator(
    repo: ProjectRepository,
    audio: CapturePort,
    paths: AudioFilePathProvider = TempDirAudioFilePathProvider(),
    dispatchers: AppDispatchers = TestAppDispatchers(),
): ProjectRecordingCoordinator =
    ProjectRecordingCoordinator(
        repo = repo,
        capture = audio,
        audioFilePathProvider = paths,
        wavPunchSplicer = WavPunchSplicer(),
        dispatchers = dispatchers,
    )
