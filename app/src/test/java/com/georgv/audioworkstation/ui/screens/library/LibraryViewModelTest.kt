package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {

    @get:Rule
    val mainDispatcherRule = LibraryMainDispatcherRule()

    @Test
    fun `uiState exposes projects from repository`() = runTest {
        val newerProject = project(id = "project-b", name = "Beta", createdAt = 2L)
        val olderProject = project(id = "project-a", name = "Alpha", createdAt = 1L)
        val dao = FakeLibraryProjectDao(projects = listOf(olderProject, newerProject))
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore))
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()

        assertEquals(listOf("project-b", "project-a"), vm.uiState.value.projects.map { it.id })
        assertEquals(listOf("Beta", "Alpha"), vm.uiState.value.projects.map { it.name })
        collectJob.cancel()
    }

    @Test
    fun `deleteProject removes project from repository state`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Alpha", createdAt = 1L),
                project(id = "project-b", name = "Beta", createdAt = 2L)
            )
        )
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore))
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()
        vm.deleteProject("project-b")
        advanceUntilIdle()

        assertEquals(listOf("project-a"), vm.uiState.value.projects.map { it.id })
        collectJob.cancel()
    }

    @Test
    fun `deleteProject emits error when delete fails`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(project(id = "project-a", name = "Alpha", createdAt = 1L)),
            failDeleteProject = true
        )
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore))

        vm.deleteProject("project-a")
        advanceUntilIdle()

        assertEquals(R.string.error_delete_project_failed, vm.userMessages.first().resId)
        assertEquals(listOf("project-a"), dao.observeProjects().first().map { it.id })
    }

    private fun project(id: String, name: String, createdAt: Long) = ProjectEntity(
        id = id,
        name = name,
        createdAt = createdAt
    )
}

private object NoopLibraryProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit
    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

private class FakeLibraryProjectDao(
    projects: List<ProjectEntity> = emptyList(),
    private val failDeleteProject: Boolean = false
) : ProjectDao {
    private val projectsFlow = MutableStateFlow(projects.sortedByDescending { it.createdAt })

    override suspend fun upsertProject(project: ProjectEntity) {
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
    override fun starting(description: Description) {
        Dispatchers.setMain(kotlinx.coroutines.test.StandardTestDispatcher())
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
