package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.audio.ProjectSampleRate
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class CreateProjectViewModelTest {

    @get:Rule
    val mainDispatcherRule = CreateProjectMainDispatcherRule()

    @Test
    fun `createProject rejects blank names`() = runTest {
        val dao = FakeCreateProjectDao()
        val vm = CreateProjectViewModel(ProjectRepository(dao, NoopCreateProjectFileStore))

        vm.onProjectNameChange("   ")
        vm.createProject()
        advanceUntilIdle()

        assertEquals(R.string.error_project_name_blank, vm.userMessages.first().resId)
        assertTrueProjects(emptyList(), dao.observeProjects().first())
    }

    @Test
    fun `createProject trims name persists defaults and emits created project id`() = runTest {
        val dao = FakeCreateProjectDao()
        val vm = CreateProjectViewModel(ProjectRepository(dao, NoopCreateProjectFileStore))

        vm.onProjectNameChange("  Demo Project  ")
        vm.createProject()
        advanceUntilIdle()

        val createdProjectId = vm.createdProjects.first()
        val createdProject = dao.observeProjects().first().single()

        assertEquals(createdProjectId, createdProject.id)
        assertEquals("Demo Project", createdProject.name)
        assertEquals(48_000, createdProject.sampleRate)
        assertEquals(16, createdProject.fileBitDepth)
        assertFalse(vm.uiState.value.isSaving)
    }

    @Test
    fun `onSampleRateChange updates ui state`() = runTest {
        val dao = FakeCreateProjectDao()
        val vm = CreateProjectViewModel(ProjectRepository(dao, NoopCreateProjectFileStore))

        assertEquals(ProjectSampleRate.Default, vm.uiState.value.sampleRate)

        vm.onSampleRateChange(ProjectSampleRate.RATE_44_100)
        assertEquals(ProjectSampleRate.RATE_44_100, vm.uiState.value.sampleRate)

        vm.onSampleRateChange(ProjectSampleRate.RATE_48_000)
        assertEquals(ProjectSampleRate.RATE_48_000, vm.uiState.value.sampleRate)
    }

    @Test
    fun `createProject persists selected sample rate`() = runTest {
        val dao = FakeCreateProjectDao()
        val vm = CreateProjectViewModel(ProjectRepository(dao, NoopCreateProjectFileStore))

        vm.onProjectNameChange("Mixtape")
        vm.onSampleRateChange(ProjectSampleRate.RATE_44_100)
        vm.createProject()
        advanceUntilIdle()

        val createdProject = dao.observeProjects().first().single()
        assertEquals(44_100, createdProject.sampleRate)
    }

    @Test
    fun `createProject emits error when persistence fails`() = runTest {
        val dao = FakeCreateProjectDao(failUpsertProject = true)
        val vm = CreateProjectViewModel(ProjectRepository(dao, NoopCreateProjectFileStore))

        vm.onProjectNameChange("Demo Project")
        vm.createProject()
        advanceUntilIdle()

        assertEquals(R.string.error_create_project_failed, vm.userMessages.first().resId)
        assertTrueProjects(emptyList(), dao.observeProjects().first())
        assertFalse(vm.uiState.value.isSaving)
    }

    private fun assertTrueProjects(expected: List<ProjectEntity>, actual: List<ProjectEntity>) {
        assertEquals(expected, actual)
    }
}

private object NoopCreateProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit
    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

private class FakeCreateProjectDao(
    projects: List<ProjectEntity> = emptyList(),
    private val failUpsertProject: Boolean = false
) : ProjectDao {
    private val projectsFlow = MutableStateFlow(projects.sortedByDescending { it.createdAt })

    override suspend fun upsertProject(project: ProjectEntity) {
        if (failUpsertProject) error("upsertProject failed")
        projectsFlow.value = (projectsFlow.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.createdAt }
    }

    override fun observeProjects(): Flow<List<ProjectEntity>> = projectsFlow

    override fun observeProject(projectId: String): Flow<ProjectEntity?> =
        projectsFlow.map { projects -> projects.firstOrNull { it.id == projectId } }

    override suspend fun projectExists(projectId: String): Boolean =
        projectsFlow.value.any { it.id == projectId }

    override suspend fun deleteProject(projectId: String) {
        projectsFlow.value = projectsFlow.value.filterNot { it.id == projectId }
    }

    override fun observeTracks(projectId: String): Flow<List<TrackEntity>> = MutableStateFlow(emptyList())

    override suspend fun upsertTrack(track: TrackEntity) = Unit

    override suspend fun upsertTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun updateTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun deleteTrack(trackId: String) = Unit
}

@OptIn(ExperimentalCoroutinesApi::class)
class CreateProjectMainDispatcherRule : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
