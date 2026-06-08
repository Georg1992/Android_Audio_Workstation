package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.MixdownOutputValidator
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.audio.ProjectMixdownCoordinator
import com.georgv.audioworkstation.core.audio.ProjectOfflineMixdownRenderer
import com.georgv.audioworkstation.core.audio.TempDirAudioFilePathProvider
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.georgv.audioworkstation.core.coroutines.AudioIoScope
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.core.ui.DataAvailability
import com.georgv.audioworkstation.core.ui.isContentEmpty
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.projects.FakeAudioController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = LibraryMainDispatcherRule()

    @Test
    fun `initial state is Pending before repository emits`() = runTest {
        val dao = PendingEmitLibraryProjectDao(
            projects = listOf(project("project-a", "Alpha", 1L)),
        )
        val repo = ProjectRepository(dao, NoopLibraryProjectFileStore)
        val vm = LibraryViewModel(repo, mixdownCoordinator(), LibraryMixPreviewPlayer())

        assertEquals(DataAvailability.Pending, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.isInitialLoad)
    }

    @Test
    fun `state becomes Ready after projects emission`() = runTest {
        val newerProject = project(id = "project-b", name = "Beta", createdAt = 2L)
        val olderProject = project(id = "project-a", name = "Alpha", createdAt = 1L)
        val dao = FakeLibraryProjectDao(projects = listOf(olderProject, newerProject))
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), LibraryMixPreviewPlayer())
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()

        assertEquals(DataAvailability.Ready, vm.uiState.value.availability)
        assertEquals(listOf("project-b", "project-a"), vm.uiState.value.content.projects.map { it.project.id })
        assertEquals(listOf("Beta", "Alpha"), vm.uiState.value.content.projects.map { it.project.name })
        collectJob.cancel()
    }

    @Test
    fun `empty projects after Ready is content empty not initial load`() = runTest {
        val dao = FakeLibraryProjectDao(projects = emptyList())
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), LibraryMixPreviewPlayer())
        backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()

        assertEquals(DataAvailability.Ready, vm.uiState.value.availability)
        assertFalse(vm.uiState.value.isInitialLoad)
        assertTrue(vm.uiState.value.isContentEmpty { it.projects.isEmpty() })
    }

    @Test
    fun `cached projects show Ready immediately on new ViewModel`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Alpha", createdAt = 1L),
                project(id = "project-b", name = "Beta", createdAt = 2L),
            )
        )
        val repo = ProjectRepository(dao, NoopLibraryProjectFileStore)
        runBlocking {
            withContext(Dispatchers.Default) {
                repo.projectsReady.first { it }
            }
        }

        assertTrue(repo.projectsReady.value)

        val vm = LibraryViewModel(repo, mixdownCoordinator(), LibraryMixPreviewPlayer())
        assertEquals(DataAvailability.Ready, vm.uiState.value.availability)
        assertEquals(2, vm.uiState.value.content.projects.size)
        assertFalse(vm.uiState.value.isInitialLoad)
    }

    @Test
    fun `deleteProject removes project from repository state`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Alpha", createdAt = 1L),
                project(id = "project-b", name = "Beta", createdAt = 2L)
            )
        )
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), LibraryMixPreviewPlayer())
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()
        vm.deleteProject("project-b")
        val projects =
            vm.uiState.dropWhile { it.content.projects.size != 1 }.first().content.projects.map { it.project.id }

        assertEquals(listOf("project-a"), projects)
        collectJob.cancel()
    }

    @Test
    fun `deleteProject emits error when delete fails`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(project(id = "project-a", name = "Alpha", createdAt = 1L)),
            failDeleteProject = true
        )
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), LibraryMixPreviewPlayer())

        vm.deleteProject("project-a")
        advanceUntilIdle()

        assertEquals(R.string.error_delete_project_failed, vm.userMessages.first().resId)
        assertEquals(listOf("project-a"), dao.observeProjects().first().map { it.id })
    }

    @Test
    fun `card body click without mix emits no mix yet message`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(project(id = "project-a", name = "Alpha", createdAt = 1L)),
        )
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), LibraryMixPreviewPlayer())
        val item = vm.uiState.value.content.projects.first()
        vm.onProjectCardBodyClick(item)
        advanceUntilIdle()
        assertEquals(R.string.library_no_mix_yet, vm.userMessages.first().resId)
    }

    @Test
    fun `library item with valid on disk mix is preview eligible`() = runTest {
        val paths = TempDirAudioFilePathProvider()
        val projectId = "project-a"
        val mixFile = File(paths.projectRecordingDirectory(projectId), "mixdown.wav")
        writeConstantPcm16Wav(
            file = mixFile,
            sampleValue = 1_000,
            frameCount = 4_410,
            sampleRateHz = 44_100,
        )
        val dao =
            FakeLibraryProjectDao(
                projects = listOf(project(id = projectId, name = "Alpha", createdAt = 1L)),
            )
        val repo = ProjectRepository(dao, NoopLibraryProjectFileStore)
        val coordinator = mixdownCoordinator(paths, repo)
        coordinator.refreshKnownMixdownPaths(listOf(projectId))
        advanceUntilIdle()
        val vm = LibraryViewModel(repo, coordinator, LibraryMixPreviewPlayer())
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        val item = vm.uiState.value.content.projects.first()
        assertTrue(File(item.mixdown.mixdownWavPath!!).isFile)
        assertEquals(
            LibraryCardBodyClickOutcome.TogglePreview,
            resolveLibraryCardBodyClick(
                mixdown = item.mixdown,
                isCurrentlyPlaying = false,
                mixFileExists = true,
            ),
        )
    }

    private fun project(
        id: String,
        name: String,
        createdAt: Long,
        sampleRate: Int = 44_100,
    ) = ProjectEntity(
        id = id,
        name = name,
        createdAt = createdAt,
        sampleRate = sampleRate,
    )

    private fun mixdownCoordinator(
        paths: AudioFilePathProvider = TempDirAudioFilePathProvider(),
        repo: ProjectRepository = ProjectRepository(FakeLibraryProjectDao(), NoopLibraryProjectFileStore),
    ): ProjectMixdownCoordinator =
        ProjectMixdownCoordinator(
            repo = repo,
            audioFilePathProvider = paths,
            offlineMixdownRenderer = ProjectOfflineMixdownRenderer(FakeAudioController()),
            mixdownOutputValidator = MixdownOutputValidator(),
            audioController = FakeAudioController(),
            dispatchers = TestAppDispatchers(),
            audioIoScope = AudioIoScope(TestAppDispatchers()),
        )
}

private object NoopLibraryProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit
    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

private class PendingEmitLibraryProjectDao(
    private val projects: List<ProjectEntity>,
) : ProjectDao by FakeLibraryProjectDao(projects = emptyList()) {
    override fun observeProjects(): Flow<List<ProjectEntity>> = flow {
        emitGate.first { it }
        emit(projects.sortedByDescending { it.createdAt })
    }

    private val emitGate = MutableStateFlow(false)
}

private class FakeLibraryProjectDao(
    projects: List<ProjectEntity> = emptyList(),
    private val failDeleteProject: Boolean = false
) : ProjectDao {
    private val projectsFlow = MutableStateFlow(projects.sortedByDescending { it.createdAt })

    override suspend fun insertProject(project: ProjectEntity) {
        projectsFlow.value = (projectsFlow.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.createdAt }
    }

    override suspend fun updateProject(project: ProjectEntity) {
        projectsFlow.value = (projectsFlow.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.createdAt }
    }

    override fun observeProjects(): Flow<List<ProjectEntity>> = projectsFlow

    override fun observeProject(projectId: String): Flow<ProjectEntity?> =
        projectsFlow.map { projects -> projects.firstOrNull { it.id == projectId } }

    override suspend fun projectExists(projectId: String): Boolean =
        projectsFlow.value.any { it.id == projectId }

    override suspend fun deleteProject(projectId: String) {
        if (failDeleteProject) error("deleteProject failed")
        projectsFlow.value = projectsFlow.value.filterNot { it.id == projectId }
    }

    override fun observeTracks(projectId: String): Flow<List<TrackEntity>> = MutableStateFlow(emptyList())

    override suspend fun upsertTrack(track: TrackEntity) = Unit

    override suspend fun upsertTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun updateTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun deleteTrack(trackId: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryMainDispatcherRule : TestWatcher() {
    val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
