package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.screens.community.CommunityViewModel
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectShareTest {
    @get:Rule
    val mainDispatcherRule = ShareMainDispatcherRule()

    @Test
    fun `sha256 of abc`() {
        val file = File.createTempFile("sha256", ".bin")
        file.writeBytes("abc".toByteArray(Charsets.UTF_8))
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Sha256Hex.ofFile(file),
        )
        file.delete()
    }

    @Test
    fun `signed out share opens login then uploads without removing phone files`() = runTest {
        val audio = "WAV-BYTES-UNIQUE-9f3a".toByteArray(Charsets.UTF_8)
        val file = File.createTempFile("take", ".wav")
        file.writeBytes(audio)
        val project = ProjectEntity(id = "local-1", name = "Night Take")
        val dao = ReadOnlyShareProjectDao(
            project = project,
            tracks = listOf(
                TrackEntity(id = "track-1", projectId = project.id, wavFilePath = file.absolutePath),
                TrackEntity(id = "track-empty", projectId = project.id, wavFilePath = ""),
            ),
        )
        val sessions = MemoryAccountSessionStore()
        val api = RecordingOnlineApi()
        val pending = PendingProjectShare()
        val coordinator = coordinator(sessions, api, dao, pending)
        val vm = CommunityViewModel(sessions, api, coordinator, TestAppDispatchers.unified(mainDispatcherRule.dispatcher))
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        assertEquals(ShareResult.NeedsLogin, coordinator.share(project.id, "Night Take"))
        assertEquals(emptyList<String>(), api.calls)

        vm.signIn("Ada@Example.com", "secret")
        advanceUntilIdle()

        assertEquals("ada@example.com", vm.uiState.value.signedInEmail)
        assertEquals(listOf("session", "project", "key", "write", "commit"), api.calls)
        assertEquals(audio.toList(), file.readBytes().toList())
        assertEquals(listOf(project), dao.projects.value)
        collectJob.cancel()
        file.delete()
    }

    @Test
    fun `rejected session is signed out and the share continues after the next login`() = runTest {
        val file = File.createTempFile("take", ".wav")
        file.writeBytes("take".toByteArray(Charsets.UTF_8))
        val project = ProjectEntity(id = "local-1", name = "Night Take")
        val dao = ReadOnlyShareProjectDao(
            project = project,
            tracks = listOf(TrackEntity(id = "track-1", projectId = project.id, wavFilePath = file.absolutePath)),
        )
        val sessions = MemoryAccountSessionStore()
        sessions.save(AccountSession(token = "stale", accountId = "account-1", email = "ada@example.com"))
        val api = RecordingOnlineApi(unauthorizedCreates = 1)
        val coordinator = coordinator(sessions, api, dao, PendingProjectShare())

        assertEquals(ShareResult.NeedsLogin, coordinator.share(project.id, "Night Take"))
        assertNull(sessions.current())
        assertEquals(emptyList<String>(), api.calls)

        sessions.save(AccountSession(token = "fresh", accountId = "account-1", email = "ada@example.com"))
        assertEquals("shared-1", coordinator.completePending())
        assertEquals(listOf("project", "key", "write", "commit"), api.calls)
        assertTrue(file.isFile)
        file.delete()
    }

    @Test
    fun `missing audio fails before a shared project is created`() = runTest {
        val project = ProjectEntity(id = "local-1", name = "Night Take")
        val dao = ReadOnlyShareProjectDao(
            project = project,
            tracks = listOf(TrackEntity(id = "track-1", projectId = project.id, wavFilePath = "missing.wav")),
        )
        val sessions = MemoryAccountSessionStore()
        sessions.save(AccountSession(token = "token", accountId = "account-1", email = "ada@example.com"))
        val api = RecordingOnlineApi()
        val coordinator = coordinator(sessions, api, dao, PendingProjectShare())

        val error = runCatching { coordinator.share(project.id, "Night Take") }.exceptionOrNull()
        assertTrue(error is ShareException)
        assertEquals(emptyList<String>(), api.calls)
        assertEquals(listOf(project), dao.projects.value)
    }

    private fun coordinator(
        sessions: MemoryAccountSessionStore,
        api: RecordingOnlineApi,
        dao: ReadOnlyShareProjectDao,
        pending: PendingProjectShare,
    ): ProjectShareCoordinator =
        ProjectShareCoordinator(
            sessions = sessions,
            api = api,
            projects = ProjectRepository(dao, ThrowingProjectFileStore),
            pending = pending,
            dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
        )
}

private class MemoryAccountSessionStore : AccountSessionStore {
    private val session = MutableStateFlow<AccountSession?>(null)
    override val state = session
    override suspend fun current(): AccountSession? = session.value
    override suspend fun save(session: AccountSession) {
        this.session.value = session
    }
    override suspend fun clear() {
        session.value = null
    }
}

private class RecordingOnlineApi(
    private var unauthorizedCreates: Int = 0,
) : OnlineApi {
    val calls = mutableListOf<String>()

    override suspend fun createAccount(email: String, password: String): AccountSession = signedIn()

    override suspend fun createSession(email: String, password: String): AccountSession {
        calls.add("session")
        return signedIn()
    }

    override suspend fun deleteSession(token: String) {
        calls.add("delete")
    }

    override suspend fun createSharedProject(token: String, title: String): SharedProject {
        if (unauthorizedCreates > 0) {
            unauthorizedCreates -= 1
            throw OnlineApiException(HttpUnauthorized, "unauthorized")
        }
        calls.add("project")
        return SharedProject(id = "shared-1", ownerAccountId = "account-1", title = title)
    }

    override suspend fun requestStorageKey(token: String, projectId: String, request: StorageKeyRequest): String {
        calls.add("key")
        return request.contentHash
    }

    override suspend fun writeStoredFile(token: String, storageKey: String, file: File) {
        calls.add("write")
    }

    override suspend fun commitStoredFile(token: String, projectId: String, file: SharedFileCommit) {
        calls.add("commit")
        assertEquals(file.contentHash, file.storageKey)
    }

    private fun signedIn(): AccountSession =
        AccountSession(token = "token", accountId = "account-1", email = "ada@example.com")
}

private class ReadOnlyShareProjectDao(
    project: ProjectEntity,
    tracks: List<TrackEntity>,
) : ProjectDao {
    val projects = MutableStateFlow(listOf(project))
    private val tracks = MutableStateFlow(tracks)

    override suspend fun insertProject(project: ProjectEntity) = error("share must not write the library")

    override suspend fun updateProject(project: ProjectEntity) = error("share must not write the library")

    override fun observeProjects(): Flow<List<ProjectEntity>> = projects

    override fun observeProject(projectId: String): Flow<ProjectEntity?> =
        projects.map { list -> list.firstOrNull { it.id == projectId } }

    override suspend fun projectExists(projectId: String): Boolean = projects.value.any { it.id == projectId }

    override suspend fun deleteProject(projectId: String) = error("share must not write the library")

    override fun observeTracks(projectId: String): Flow<List<TrackEntity>> =
        tracks.map { list -> list.filter { it.projectId == projectId } }

    override suspend fun upsertTrack(track: TrackEntity) = error("share must not write the library")

    override suspend fun upsertTracks(tracks: List<TrackEntity>) = error("share must not write the library")

    override suspend fun updateTracks(tracks: List<TrackEntity>) = error("share must not write the library")

    override suspend fun deleteTrack(trackId: String) = error("share must not write the library")
}

private object ThrowingProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = error("share must not delete phone files")

    override suspend fun deleteProjectFolder(projectId: String) = error("share must not delete phone files")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareMainDispatcherRule : TestWatcher() {
    val dispatcher = StandardTestDispatcher()

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
