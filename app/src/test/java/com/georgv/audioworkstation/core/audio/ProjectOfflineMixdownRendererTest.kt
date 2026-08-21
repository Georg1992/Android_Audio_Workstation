package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.audio.mixdownTimelineEndMs
import com.georgv.audioworkstation.core.audio.FakeAudioController
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ProjectOfflineMixdownRendererTest {

    @Test
    fun `render builds spec and delegates to native mixdown controller`() = runTest {
        val paths = TempDirAudioFilePathProvider()
        val projectDir = File(paths.projectRecordingDirectory("project-a"))
        val trackPath = File(projectDir, "track-a.wav")
        writeConstantPcm16Wav(
            file = trackPath,
            sampleValue = 1_000,
            frameCount = 4_410,
            sampleRateHz = 44_100,
        )
        val outputPath = File(projectDir, "mixdown.wav").absolutePath
        val audioController =
            object : MixdownPort by FakeAudioController() {
                override suspend fun renderOfflineMixdown(
                    spec: MultiPlaybackSpec,
                    outputPath: String,
                    onProgress: (Float) -> Unit,
                ): MixdownResult {
                    assertEquals(0L, spec.startPositionMs)
                    assertEquals(1_000L, spec.sessionTimelineEndMs)
                    assertEquals(1, spec.lanes.size)
                    onProgress(1f)
                    return MixdownResult.Success(outputPath)
                }
            }
        val renderer = ProjectOfflineMixdownRenderer(audioController)
        val project = ProjectEntity(id = "project-a", name = "Mix", sampleRate = 44_100)
        val track =
            TrackEntity(
                id = "track-a",
                projectId = project.id,
                wavFilePath = trackPath.absolutePath,
                duration = 1_000L,
                importStatus = TrackImportStatus.READY,
            )

        val result =
            renderer.render(
                project = project,
                tracks = listOf(track),
                selectedTrackIds = setOf(track.id),
                outputPath = outputPath,
                onProgress = {},
            )

        assertTrue(result is OfflineMixdownResult.Success)
        assertEquals(outputPath, (result as OfflineMixdownResult.Success).outputPath)
    }

    @Test
    fun `render includes only selected playable tracks`() = runTest {
        val paths = TempDirAudioFilePathProvider()
        val projectDir = File(paths.projectRecordingDirectory("project-a"))
        val trackAPath = File(projectDir, "a.wav")
        val trackBPath = File(projectDir, "b.wav")
        writeConstantPcm16Wav(file = trackAPath, sampleValue = 500, frameCount = 100, sampleRateHz = 44_100)
        writeConstantPcm16Wav(file = trackBPath, sampleValue = 700, frameCount = 100, sampleRateHz = 44_100)
        val outputPath = File(projectDir, "mixdown.wav").absolutePath
        val audioController =
            object : MixdownPort by FakeAudioController() {
                override suspend fun renderOfflineMixdown(
                    spec: MultiPlaybackSpec,
                    outputPath: String,
                    onProgress: (Float) -> Unit,
                ): MixdownResult {
                    assertEquals(listOf("b"), spec.lanes.map { it.trackId })
                    assertEquals(20_000L, spec.sessionTimelineEndMs)
                    return MixdownResult.Success(outputPath)
                }
            }
        val renderer = ProjectOfflineMixdownRenderer(audioController)
        val project = ProjectEntity(id = "project-a", name = "Mix", sampleRate = 44_100)
        val tracks =
            listOf(
                TrackEntity(
                    id = "a",
                    projectId = project.id,
                    position = 0,
                    wavFilePath = trackAPath.absolutePath,
                    duration = 10_000L,
                    importStatus = TrackImportStatus.READY,
                ),
                TrackEntity(
                    id = "b",
                    projectId = project.id,
                    position = 1,
                    wavFilePath = trackBPath.absolutePath,
                    duration = 20_000L,
                    importStatus = TrackImportStatus.READY,
                ),
            )

        val result =
            renderer.render(
                project = project,
                tracks = tracks,
                selectedTrackIds = setOf("b"),
                outputPath = outputPath,
                onProgress = {},
            )

        assertTrue(result is OfflineMixdownResult.Success)
    }

    @Test
    fun `render spans global timeline from zero through furthest clip end`() = runTest {
        val paths = TempDirAudioFilePathProvider()
        val projectDir = File(paths.projectRecordingDirectory("project-a"))
        val trackPath = File(projectDir, "track-a.wav")
        writeConstantPcm16Wav(
            file = trackPath,
            sampleValue = 1_000,
            frameCount = 4_410,
            sampleRateHz = 44_100,
        )
        val outputPath = File(projectDir, "mixdown.wav").absolutePath
        val audioController =
            object : MixdownPort by FakeAudioController() {
                override suspend fun renderOfflineMixdown(
                    spec: MultiPlaybackSpec,
                    outputPath: String,
                    onProgress: (Float) -> Unit,
                ): MixdownResult {
                    assertEquals(0L, spec.startPositionMs)
                    assertEquals(15_000L, spec.sessionTimelineEndMs)
                    return MixdownResult.Success(outputPath)
                }
            }
        val renderer = ProjectOfflineMixdownRenderer(audioController)
        val project = ProjectEntity(id = "project-a", name = "Mix", sampleRate = 44_100)
        val track =
            TrackEntity(
                id = "track-a",
                projectId = project.id,
                wavFilePath = trackPath.absolutePath,
                duration = 10_000L,
                timelineStartOffsetMs = 5_000L,
                importStatus = TrackImportStatus.READY,
            )

        val result =
            renderer.render(
                project = project,
                tracks = listOf(track),
                selectedTrackIds = setOf(track.id),
                outputPath = outputPath,
                onProgress = {},
            )

        assertTrue(result is OfflineMixdownResult.Success)
    }

    @Test
    fun `render fails when no playable tracks`() = runTest {
        val renderer = ProjectOfflineMixdownRenderer(FakeAudioController())
        val project = ProjectEntity(id = "project-a", name = "Empty", sampleRate = 44_100)
        val result =
            renderer.render(
                project = project,
                tracks = emptyList(),
                selectedTrackIds = emptySet(),
                outputPath = File.createTempFile("mixdown-empty", ".wav").absolutePath,
                onProgress = {},
            )
        assertEquals(OfflineMixdownResult.NoPlayableTracks, result)
    }
}
