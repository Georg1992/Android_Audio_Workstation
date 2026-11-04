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
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.ui.isContentEmpty
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import androidx.lifecycle.SavedStateHandle
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.share.CardImageStore
import com.georgv.audioworkstation.online.share.ProjectShare
import com.georgv.audioworkstation.online.share.SharedCardUpdate
import com.georgv.audioworkstation.online.share.SharedPlayback
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.SharedProjects
import com.georgv.audioworkstation.online.share.SharedTake
import javax.inject.Provider
import com.georgv.audioworkstation.core.audio.FakeAudioController
import android.net.Uri
import java.net.ConnectException
import java.net.UnknownHostException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
        val vm = LibraryViewModel(repo, mixdownCoordinator(), libraryPreviewPlayer(), IdleProjectShare())

        assertEquals(DataAvailability.Pending, vm.uiState.value.availability)
        assertTrue(vm.uiState.value.isInitialLoad)
    }

    @Test
    fun `state becomes Ready after projects emission`() = runTest {
        val newerProject = project(id = "project-b", name = "Beta", createdAt = 2L)
        val olderProject = project(id = "project-a", name = "Alpha", createdAt = 1L)
        val dao = FakeLibraryProjectDao(projects = listOf(olderProject, newerProject))
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), libraryPreviewPlayer(), IdleProjectShare())
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
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), libraryPreviewPlayer(), IdleProjectShare())
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

        val vm = LibraryViewModel(repo, mixdownCoordinator(), libraryPreviewPlayer(), IdleProjectShare())
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
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), libraryPreviewPlayer(), IdleProjectShare())
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
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), libraryPreviewPlayer(), IdleProjectShare())

        vm.deleteProject("project-a")
        advanceUntilIdle()

        assertEquals(R.string.error_delete_project_failed, vm.userMessages.first().resId)
        assertEquals(listOf("project-a"), dao.observeProjects().first().map { it.id })
    }

    @Test
    fun `delete of a shared project unshares it before the local row is removed`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Boom", createdAt = 1L).copy(syncStatus = SyncStatus.SYNCED),
            ),
        )
        val share = RecordingProjectShare { dao.observeProject("project-a").first() }
        val vm = LibraryViewModel(
            ProjectRepository(dao, NoopLibraryProjectFileStore),
            mixdownCoordinator(),
            libraryPreviewPlayer(),
            share,
        )
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()
        vm.deleteProject("project-a")
        vm.uiState.dropWhile { it.content.projects.isNotEmpty() }.first()

        assertEquals(listOf("project-a"), share.unshared)
        assertEquals(SyncStatus.SYNCED, share.seenWhileUnsharing.single()?.syncStatus)
        assertTrue(dao.observeProjects().first().isEmpty())
        collectJob.cancel()
    }

    @Test
    fun `delete of a project that is still being shared unshares it first`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Showcase", createdAt = 1L).copy(syncStatus = SyncStatus.SYNCING),
            ),
        )
        val share = RecordingProjectShare { dao.observeProject("project-a").first() }
        val vm = LibraryViewModel(
            ProjectRepository(dao, NoopLibraryProjectFileStore),
            mixdownCoordinator(),
            libraryPreviewPlayer(),
            share,
        )
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()
        vm.deleteProject("project-a")
        vm.uiState.dropWhile { it.content.projects.isNotEmpty() }.first()

        assertEquals(listOf("project-a"), share.unshared)
        assertEquals(SyncStatus.SYNCING, share.seenWhileUnsharing.single()?.syncStatus)
        assertTrue(dao.observeProjects().first().isEmpty())
        collectJob.cancel()
    }

    @Test
    fun `delete of a local project does not unshare it`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(project(id = "project-a", name = "Alpha", createdAt = 1L)),
        )
        val share = RecordingProjectShare()
        val vm = LibraryViewModel(
            ProjectRepository(dao, NoopLibraryProjectFileStore),
            mixdownCoordinator(),
            libraryPreviewPlayer(),
            share,
        )
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        advanceUntilIdle()
        vm.deleteProject("project-a")
        vm.uiState.dropWhile { it.content.projects.isNotEmpty() }.first()

        assertTrue(share.unshared.isEmpty())
        assertTrue(dao.observeProjects().first().isEmpty())
        collectJob.cancel()
    }

    @Test
    fun `delete keeps a shared project when unshare cannot reach the host`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Boom", createdAt = 1L).copy(syncStatus = SyncStatus.SYNCED),
            ),
        )
        val share = RecordingProjectShare(failure = UnknownHostException("Unable to resolve host"))
        val vm = LibraryViewModel(
            ProjectRepository(dao, NoopLibraryProjectFileStore),
            mixdownCoordinator(),
            libraryPreviewPlayer(),
            share,
        )

        vm.deleteProject("project-a")
        advanceUntilIdle()

        assertEquals(R.string.error_no_connection, vm.userMessages.first().resId)
        assertEquals(listOf("project-a"), dao.observeProjects().first().map { it.id })
        assertEquals(SyncStatus.SYNCED, dao.observeProject("project-a").first()?.syncStatus)
    }

    @Test
    fun `delete keeps a shared project when the server rejects unshare`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Boom", createdAt = 1L).copy(syncStatus = SyncStatus.SYNCED),
            ),
        )
        val share = RecordingProjectShare(failure = IllegalStateException("share api status 500"))
        val vm = LibraryViewModel(
            ProjectRepository(dao, NoopLibraryProjectFileStore),
            mixdownCoordinator(),
            libraryPreviewPlayer(),
            share,
        )

        vm.deleteProject("project-a")
        advanceUntilIdle()

        assertEquals(R.string.error_unshare_project_failed, vm.userMessages.first().resId)
        assertEquals(SyncStatus.SYNCED, dao.observeProject("project-a").first()?.syncStatus)
    }

    @Test
    fun `play without mix emits no mix yet message`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(project(id = "project-a", name = "Alpha", createdAt = 1L)),
        )
        val vm = LibraryViewModel(ProjectRepository(dao, NoopLibraryProjectFileStore), mixdownCoordinator(), libraryPreviewPlayer(), IdleProjectShare())
        val preview = LibraryPreviewViewModel(libraryPreviewPlayer())
        val item = vm.uiState.value.content.projects.first()
        preview.play(item)
        advanceUntilIdle()
        assertEquals(R.string.library_no_mix_yet, preview.userMessages.first().resId)
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
        val vm = LibraryViewModel(repo, coordinator, libraryPreviewPlayer(), IdleProjectShare())
        backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()

        val item = vm.uiState.value.content.projects.first()
        assertTrue(item.mixdown.hasMixPreview)
        assertTrue(File(item.mixdown.mixdownWavPath!!).isFile)
    }

    @Test
    fun `saving a shared card publishes and does not report a save failure`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project(id = "project-a", name = "Alpha", createdAt = 1L)
                    .copy(syncStatus = SyncStatus.SYNCED),
            ),
        )
        val published = CompletableDeferred<String>()
        val publishThread = CompletableDeferred<Thread>()
        var tagsSeen = ""
        var nameDuringPublish: String? = null
        val share = object : SharedCardUpdate {
            override suspend fun updateCard(
                projectId: String,
                title: String,
                tags: String,
                imagePath: String?,
            ) {
                nameDuringPublish = dao.observeProject(projectId).first()?.name
                tagsSeen = tags
                publishThread.complete(Thread.currentThread())
                published.complete(title)
            }
        }
        val vm = LibraryCardViewModel(
            SavedStateHandle(mapOf("projectId" to "project-a")),
            publishingRepository(dao, share),
            TempDirAudioFilePathProvider(),
            IdleCardImages,
        )
        val errors = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val saved = CompletableDeferred<Unit>()
        val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watch.launch { vm.userMessages.collect { errors += it.resId } }
        watch.launch { vm.savedEvent.collect { saved.complete(Unit) } }
        advanceUntilIdle()

        vm.setTitle("Renamed")
        vm.setTags("drums, live")
        vm.save()
        advanceUntilIdle()
        val deadline = System.nanoTime() + 5_000_000_000L
        while ((!published.isCompleted || !saved.isCompleted) && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }

        try {
            assertEquals("Renamed", published.getCompleted())
            assertEquals("drums, live", tagsSeen)
            assertEquals("Alpha", nameDuringPublish)
            assertTrue(publishThread.getCompleted() != Thread.currentThread())
            assertTrue(errors.toString(), saved.isCompleted)
            assertTrue(errors.isEmpty())
            assertEquals("Renamed", dao.observeProject("project-a").first()?.name)
            assertEquals("drums, live", dao.observeProject("project-a").first()?.cardTags)
        } finally {
            watch.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun `library marks this account shared when the community row exists`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(
                project("project-a", "Showcase", 2L),
                project("project-b", "Other", 1L),
            ),
        )
        val repo = ProjectRepository(dao, NoopLibraryProjectFileStore)
        val session = AccountSession("access-token", "account-1", "owner@example.com", "Owner", "refresh-token")
        LibraryShareViewModel(
            SignedInAccountSessionStore(session),
            IdleProjectShare(),
            ListedProjects(
                listOf(
                    SharedProject("project-a", "Showcase", emptyList(), null, "Owner"),
                    SharedProject("project-b", "Other", emptyList(), null, "Someone"),
                ),
            ),
            repo,
        )
        val deadline = System.nanoTime() + 5_000_000_000L
        var mine = dao.observeProject("project-a").first()
        while (mine?.syncStatus != SyncStatus.SYNCED && System.nanoTime() < deadline) {
            Thread.sleep(20)
            mine = dao.observeProject("project-a").first()
        }
        val other = dao.observeProject("project-b").first()
        assertEquals(SyncStatus.SYNCED, mine?.syncStatus)
        assertEquals("account-1", mine?.ownerUserId)
        assertEquals(SyncStatus.LOCAL, other?.syncStatus)
        assertEquals(null, other?.ownerUserId)
    }

    @Test
    fun `share reports a connection problem when the host cannot be reached`() = runTest {
        val vm = shareVm(ThrowingProjectShare(UnknownHostException("Unable to resolve host")))
        assertEquals(R.string.error_no_connection, awaitMessage(vm.userMessages) { vm.shareProject("project-a") })
    }

    @Test
    fun `unshare reports a connection problem when the route is down`() = runTest {
        val failure = ConnectException("Failed to connect to share.example.com/203.0.113.5:443")
        failure.initCause(Exception("connect failed: ENETUNREACH (Network is unreachable)"))
        val vm = shareVm(ThrowingProjectShare(failure))
        assertEquals(R.string.error_no_connection, awaitMessage(vm.userMessages) { vm.unshareProject("project-a") })
    }

    @Test
    fun `share keeps its own failure when the server rejects the project`() = runTest {
        val vm = shareVm(ThrowingProjectShare(IllegalStateException("share api status 500")))
        assertEquals(
            R.string.error_share_project_failed,
            awaitMessage(vm.userMessages) { vm.shareProject("project-a") },
        )
    }

    @Test
    fun `saving a shared card reports a connection problem when the host cannot be reached`() = runTest {
        val dao = syncedCardDao()
        val vm = cardVm(dao, UnknownHostException("Unable to resolve host"))
        advanceUntilIdle()
        vm.setTitle("Renamed")
        vm.setTags("drums")
        assertEquals(R.string.error_no_connection, awaitMessage(vm.userMessages) { vm.save() })
        assertEquals("Alpha", dao.observeProject("project-a").first()?.name)
        assertEquals("", dao.observeProject("project-a").first()?.cardTags)
    }

    @Test
    fun `saving a shared card keeps the save failure when the server rejects it`() = runTest {
        val dao = syncedCardDao()
        val vm = cardVm(dao, IllegalStateException("share api status 500"))
        advanceUntilIdle()
        vm.setTitle("Renamed")
        assertEquals(R.string.error_card_save_failed, awaitMessage(vm.userMessages) { vm.save() })
        assertEquals("Alpha", dao.observeProject("project-a").first()?.name)
    }

    @Test
    fun `saving a local card does not publish it`() = runTest {
        val dao = FakeLibraryProjectDao(
            projects = listOf(project(id = "project-a", name = "Alpha", createdAt = 1L)),
        )
        var published = false
        val vm = LibraryCardViewModel(
            SavedStateHandle(mapOf("projectId" to "project-a")),
            publishingRepository(
                dao,
                object : SharedCardUpdate {
                    override suspend fun updateCard(
                        projectId: String,
                        title: String,
                        tags: String,
                        imagePath: String?,
                    ) {
                        published = true
                    }
                },
            ),
            TempDirAudioFilePathProvider(),
            IdleCardImages,
        )
        val saved = CompletableDeferred<Unit>()
        val watch = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        watch.launch { vm.savedEvent.collect { saved.complete(Unit) } }
        advanceUntilIdle()

        vm.setTitle("Renamed")
        vm.save()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!saved.isCompleted && System.nanoTime() < deadline) {
            Thread.sleep(20)
        }

        try {
            assertTrue(saved.isCompleted)
            assertFalse(published)
            assertEquals("Renamed", dao.observeProject("project-a").first()?.name)
        } finally {
            watch.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    private fun shareVm(share: ProjectShare): LibraryShareViewModel =
        LibraryShareViewModel(
            IdleAccountSessionStore(),
            share,
            ListedProjects(emptyList()),
            ProjectRepository(FakeLibraryProjectDao(), NoopLibraryProjectFileStore),
        )

    private fun syncedCardDao(): FakeLibraryProjectDao = FakeLibraryProjectDao(
        projects = listOf(
            project(id = "project-a", name = "Alpha", createdAt = 1L).copy(syncStatus = SyncStatus.SYNCED),
        ),
    )

    private fun publishingRepository(
        dao: FakeLibraryProjectDao,
        cards: SharedCardUpdate,
    ): ProjectRepository = ProjectRepository(
        dao,
        NoopLibraryProjectFileStore,
        sharedCards = Provider { cards },
    )

    private fun cardVm(dao: FakeLibraryProjectDao, failure: Exception): LibraryCardViewModel =
        LibraryCardViewModel(
            SavedStateHandle(mapOf("projectId" to "project-a")),
            publishingRepository(
                dao,
                object : SharedCardUpdate {
                    override suspend fun updateCard(
                        projectId: String,
                        title: String,
                        tags: String,
                        imagePath: String?,
                    ) {
                        throw failure
                    }
                },
            ),
            TempDirAudioFilePathProvider(),
            IdleCardImages,
        )

    private fun awaitMessage(messages: Flow<UiMessage>, block: () -> Unit): Int {
        val seen = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            messages.collect { seen += it.resId }
        }
        try {
            block()
            val deadline = System.nanoTime() + 5_000_000_000L
            while (seen.isEmpty() && System.nanoTime() < deadline) {
                Thread.sleep(20)
            }
            return seen.single()
        } finally {
            job.cancel()
        }
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
            mixdown = FakeAudioController(),
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

private object IdleCardImages : CardImageStore {
    override fun copy(uri: Uri): File = error("unused")
}

private class ListedProjects(private val projects: List<SharedProject>) : SharedProjects {
    override suspend fun list(): List<SharedProject> = projects

    override suspend fun imageUrl(id: String): String = error("unused")

    override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

    override suspend fun files(id: String): List<SharedTake> = error("unused")
}

private class SignedInAccountSessionStore(private val session: AccountSession) : AccountSessionStore {
    override val state: Flow<AccountSession?> = flowOf(session)

    override suspend fun current(): AccountSession? = session

    override suspend fun save(session: AccountSession) = Unit

    override suspend fun clear() = Unit
}

private class IdleAccountSessionStore : AccountSessionStore {
    override val state: Flow<AccountSession?> = flowOf(null)

    override suspend fun current(): AccountSession? = null

    override suspend fun save(session: AccountSession) = Unit

    override suspend fun clear() = Unit
}

private class IdleProjectShare : ProjectShare {
    override suspend fun share(projectId: String) = Unit

    override suspend fun unshare(projectId: String) = Unit
}

private class RecordingProjectShare(
    private val failure: Exception? = null,
    private val beforeUnshare: suspend (String) -> ProjectEntity? = { null },
) : ProjectShare {
    val unshared = mutableListOf<String>()
    val seenWhileUnsharing = mutableListOf<ProjectEntity?>()

    override suspend fun share(projectId: String) = Unit

    override suspend fun unshare(projectId: String) {
        seenWhileUnsharing += beforeUnshare(projectId)
        unshared += projectId
        failure?.let { throw it }
    }
}

private class ThrowingProjectShare(private val failure: Exception) : ProjectShare {
    override suspend fun share(projectId: String): Unit = throw failure

    override suspend fun unshare(projectId: String): Unit = throw failure
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
