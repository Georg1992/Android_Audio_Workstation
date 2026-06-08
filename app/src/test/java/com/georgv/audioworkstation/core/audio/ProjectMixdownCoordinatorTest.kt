package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.coroutines.AudioIoScope
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.FakeAudioController
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectMixdownCoordinatorTest {

    private val testDispatcher = StandardTestDispatcher()
    private val dispatchers = TestAppDispatchers.unified(testDispatcher)

    @Test
    fun `startMixdown rejects duplicate job for same project`() = runTest(testDispatcher) {
        val coordinator = coordinatorWithTempDir()
        val first = coordinator.startMixdown("project-a", setOf("track-a"))
        val second = coordinator.startMixdown("project-a", setOf("track-a"))

        assertTrue(first)
        assertFalse(second)
        assertTrue(coordinator.isMixing("project-a"))
    }

    @Test
    fun `mixdown writes mixdown wav for playable project`() = runTest(testDispatcher) {
        val paths = TempDirAudioFilePathProvider()
        val projectDir = File(paths.projectRecordingDirectory("project-a"))
        val trackPath = File(projectDir, "track-a.wav")
        writeConstantPcm16Wav(
            file = trackPath,
            sampleValue = 6_000,
            frameCount = 2_000,
            sampleRateHz = 44_100,
        )
        val project = ProjectEntity(id = "project-a", name = "Mix", sampleRate = 44_100)
        val track =
            TrackEntity(
                id = "track-a",
                projectId = project.id,
                wavFilePath = trackPath.absolutePath,
                duration = 2_000L,
                importStatus = TrackImportStatus.READY,
            )
        val repo =
            ProjectRepository(
                FakeMixdownProjectDao(project = project, tracks = listOf(track)),
                NoopMixdownProjectFileStore,
            )
        val audioController = SuccessMixdownAudioController(sampleRateHz = 44_100, durationMs = 2_000L)
        val coordinator = coordinator(paths, repo, audioController)

        assertTrue(coordinator.startMixdown("project-a", setOf("track-a")))
        advanceUntilIdle()

        val state = coordinator.mixdownState("project-a")
        assertFalse(state.isMixing)
        assertNotNull(state.mixdownWavPath)
        assertNull(state.errorMessageResId)
        assertTrue(File(state.mixdownWavPath!!).isFile)
    }

    @Test
    fun `startMixdown rejects empty selection`() = runTest(testDispatcher) {
        val coordinator = coordinatorWithTempDir()
        assertFalse(coordinator.startMixdown("project-a", emptySet()))
    }

    @Test
    fun `mixdown with no playable tracks fails without writing output`() = runTest(testDispatcher) {
        val paths = TempDirAudioFilePathProvider()
        val project = ProjectEntity(id = "project-a", name = "Empty", sampleRate = 44_100)
        val repo =
            ProjectRepository(
                FakeMixdownProjectDao(project = project, tracks = emptyList()),
                NoopMixdownProjectFileStore,
            )
        val coordinator = coordinator(paths, repo, FakeAudioController())

        assertTrue(coordinator.startMixdown("project-a", setOf("track-a")))
        advanceUntilIdle()

        val state = coordinator.mixdownState("project-a")
        assertEquals(R.string.error_no_audio_for_selected_tracks, state.errorMessageResId)
        assertNull(state.mixdownWavPath)
    }

    @Test
    fun `failed mixdown does not save broken path`() = runTest(testDispatcher) {
        val paths = TempDirAudioFilePathProvider()
        val projectDir = File(paths.projectRecordingDirectory("project-a"))
        val trackPath = File(projectDir, "track-a.wav")
        writeConstantPcm16Wav(
            file = trackPath,
            sampleValue = 6_000,
            frameCount = 2_000,
            sampleRateHz = 44_100,
        )
        val project = ProjectEntity(id = "project-a", name = "Mix", sampleRate = 44_100)
        val track =
            TrackEntity(
                id = "track-a",
                projectId = project.id,
                wavFilePath = trackPath.absolutePath,
                duration = 2_000L,
                importStatus = TrackImportStatus.READY,
            )
        val repo =
            ProjectRepository(
                FakeMixdownProjectDao(project = project, tracks = listOf(track)),
                NoopMixdownProjectFileStore,
            )
        val coordinator = coordinator(paths, repo, FakeAudioController())

        assertTrue(coordinator.startMixdown("project-a", setOf("track-a")))
        advanceUntilIdle()

        val state = coordinator.mixdownState("project-a")
        assertEquals(R.string.error_mixdown_failed, state.errorMessageResId)
        assertNull(state.mixdownWavPath)
    }

    @Test
    fun `refreshKnownMixdownPaths ignores invalid wav file`() = runTest(testDispatcher) {
        val paths = TempDirAudioFilePathProvider()
        val projectDir = File(paths.projectRecordingDirectory("project-a"))
        File(projectDir, "mixdown.wav").writeText("not-a-wav")
        val project = ProjectEntity(id = "project-a", name = "Mix", sampleRate = 44_100)
        val repo =
            ProjectRepository(
                FakeMixdownProjectDao(project = project),
                NoopMixdownProjectFileStore,
            )
        val coordinator = coordinator(paths, repo, FakeAudioController())

        coordinator.refreshKnownMixdownPaths(listOf("project-a"))

        assertNull(coordinator.mixdownState("project-a").mixdownWavPath)
    }

    @Test
    fun `refreshKnownMixdownPaths picks up valid wav file`() = runTest(testDispatcher) {
        val paths = TempDirAudioFilePathProvider()
        val projectDir = File(paths.projectRecordingDirectory("project-a"))
        val mixFile = File(projectDir, "mixdown.wav")
        writeConstantPcm16Wav(
            file = mixFile,
            sampleValue = 1_000,
            frameCount = 4_410,
            sampleRateHz = 44_100,
        )
        val project = ProjectEntity(id = "project-a", name = "Mix", sampleRate = 44_100)
        val repo =
            ProjectRepository(
                FakeMixdownProjectDao(project = project),
                NoopMixdownProjectFileStore,
            )
        val coordinator = coordinator(paths, repo, FakeAudioController())

        coordinator.refreshKnownMixdownPaths(listOf("project-a"))

        assertEquals(mixFile.absolutePath, coordinator.mixdownState("project-a").mixdownWavPath)
    }

    private fun coordinatorWithTempDir(): ProjectMixdownCoordinator =
        coordinator(
            TempDirAudioFilePathProvider(),
            ProjectRepository(FakeMixdownProjectDao(), NoopMixdownProjectFileStore),
            FakeAudioController(),
        )

    private fun coordinator(
        paths: AudioFilePathProvider,
        repo: ProjectRepository,
        audioController: AudioController,
    ): ProjectMixdownCoordinator =
        ProjectMixdownCoordinator(
            repo = repo,
            audioFilePathProvider = paths,
            offlineMixdownRenderer = ProjectOfflineMixdownRenderer(audioController),
            mixdownOutputValidator = MixdownOutputValidator(),
            audioController = audioController,
            dispatchers = dispatchers,
            audioIoScope = AudioIoScope(dispatchers),
        )
}

private class SuccessMixdownAudioController(
    private val sampleRateHz: Int,
    private val durationMs: Long,
) : AudioController by FakeAudioController() {
    override suspend fun renderOfflineMixdown(
        spec: MultiPlaybackSpec,
        outputPath: String,
        onProgress: (Float) -> Unit,
    ): MixdownResult {
        val frameCount = ((durationMs * sampleRateHz) / 1_000L).toInt().coerceAtLeast(1)
        writeConstantPcm16Wav(
            file = File(outputPath),
            sampleValue = 1_000,
            frameCount = frameCount,
            sampleRateHz = sampleRateHz,
            channelCount = 2,
        )
        onProgress(1f)
        return MixdownResult.Success(outputPath)
    }
}

private object NoopMixdownProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit

    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

private class FakeMixdownProjectDao(
    private val project: ProjectEntity? = null,
    private val tracks: List<TrackEntity> = emptyList(),
) : ProjectDao {
    override suspend fun insertProject(project: ProjectEntity) = Unit

    override suspend fun updateProject(project: ProjectEntity) = Unit

    override fun observeProjects(): Flow<List<ProjectEntity>> =
        MutableStateFlow(listOfNotNull(project))

    override fun observeProject(projectId: String): Flow<ProjectEntity?> =
        observeProjects().map { projects -> projects.firstOrNull { it.id == projectId } }

    override suspend fun projectExists(projectId: String): Boolean = project?.id == projectId

    override suspend fun deleteProject(projectId: String) = Unit

    override fun observeTracks(projectId: String): Flow<List<TrackEntity>> =
        MutableStateFlow(tracks.filter { it.projectId == projectId })

    override suspend fun upsertTrack(track: TrackEntity) = Unit

    override suspend fun upsertTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun updateTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun deleteTrack(trackId: String) = Unit
}
