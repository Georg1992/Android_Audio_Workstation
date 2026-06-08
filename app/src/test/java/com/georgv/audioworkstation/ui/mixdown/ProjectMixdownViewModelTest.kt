package com.georgv.audioworkstation.ui.mixdown

import com.georgv.audioworkstation.core.audio.MixdownOutputValidator
import com.georgv.audioworkstation.core.audio.ProjectOfflineMixdownRenderer
import com.georgv.audioworkstation.core.audio.ProjectMixdownCoordinator
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.ui.screens.projects.FakeAudioController
import com.georgv.audioworkstation.core.audio.TempDirAudioFilePathProvider
import com.georgv.audioworkstation.core.coroutines.AudioIoScope
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectMixdownViewModelTest {

    @Test
    fun `requestMixdown delegates to coordinator`() = runTest {
        val dispatcher = StandardTestDispatcher()
        val dispatchers = TestAppDispatchers.unified(dispatcher)
        val coordinator =
            ProjectMixdownCoordinator(
                repo = ProjectRepository(MixdownViewModelProjectDao(), NoopMixdownViewModelProjectFileStore),
                audioFilePathProvider = TempDirAudioFilePathProvider(),
                offlineMixdownRenderer = ProjectOfflineMixdownRenderer(FakeAudioController()),
                mixdownOutputValidator = MixdownOutputValidator(),
                audioController = FakeAudioController(),
                dispatchers = dispatchers,
                audioIoScope = AudioIoScope(dispatchers),
            )
        val vm = ProjectMixdownViewModel(coordinator)

        assertTrue(vm.requestMixdown("project-a", setOf("track-a")))
        assertTrue(vm.mixdownState("project-a").isMixing)
    }
}

private object NoopMixdownViewModelProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit

    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

private class MixdownViewModelProjectDao : ProjectDao {
    override suspend fun insertProject(project: ProjectEntity) = Unit

    override suspend fun updateProject(project: ProjectEntity) = Unit

    override fun observeProjects(): Flow<List<ProjectEntity>> = MutableStateFlow(emptyList())

    override fun observeProject(projectId: String): Flow<ProjectEntity?> =
        observeProjects().map { projects -> projects.firstOrNull { it.id == projectId } }

    override suspend fun projectExists(projectId: String): Boolean = false

    override suspend fun deleteProject(projectId: String) = Unit

    override fun observeTracks(projectId: String): Flow<List<TrackEntity>> =
        MutableStateFlow(emptyList())

    override suspend fun upsertTrack(track: TrackEntity) = Unit

    override suspend fun upsertTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun updateTracks(tracks: List<TrackEntity>) = Unit

    override suspend fun deleteTrack(trackId: String) = Unit
}
