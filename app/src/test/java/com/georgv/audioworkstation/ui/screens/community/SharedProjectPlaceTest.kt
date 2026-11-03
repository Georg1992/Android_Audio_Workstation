package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.NoopProjectFileStore
import com.georgv.audioworkstation.core.audio.TempDirAudioFilePathProvider
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.toRecordingSpec
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.core.track.hasTimelineClip
import com.georgv.audioworkstation.core.track.selectedPlayableTracks
import com.georgv.audioworkstation.core.track.streamsSharedTake
import com.georgv.audioworkstation.ui.playback.MixSource
import com.georgv.audioworkstation.data.db.dao.FakeProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.share.OwnedProjectPublish
import com.georgv.audioworkstation.online.share.SharedPlayback
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.SharedProjects
import com.georgv.audioworkstation.online.share.SharedTake
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SharedProjectPlaceTest {
    @get:Rule
    val mainDispatcher = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun openStoresSharedTakesTheProjectScreenCanPlayAndRecord() = runTest {
        val dao = FakeProjectDao()
        val paths = TempDirAudioFilePathProvider()
        val wav = wavBytes(channelCount = 2, sampleRateHz = 48_000, frames = 4_800)
        val headers = HeaderBytes(wav)
        val local = room(dao, paths, ListedTakes(wav.size.toLong()), headers)
        val vm = home(local)
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        vm.open("song")
        advanceUntilIdle()
        assertEquals("song", vm.openProject.first())
        val project = checkNotNull(dao.observeProject("song").first())
        assertEquals("Song", project.name)
        assertEquals(48_000, project.sampleRate)
        assertEquals(SyncStatus.LOCAL, project.syncStatus)
        val tracks = dao.observeTracks("song").first()
        assertEquals(listOf("take-a", "take-b"), tracks.map { it.id })
        assertEquals(listOf("Take 1", "Take 2"), tracks.map { it.name })
        assertEquals(listOf("hash-a", "hash-b"), tracks.map { it.contentHash })
        assertEquals(listOf(SyncStatus.LOCAL, SyncStatus.LOCAL), tracks.map { it.syncStatus })
        assertTrue(tracks.all { it.wavFilePath.isBlank() && it.streamsSharedTake() && it.hasTimelineClip() })
        assertTrue(tracks.all { !it.hasPersistedPlayableAudio() && (it.duration ?: 0L) > 0L })
        assertFalse(File(paths.trackOutputPath("song", "take-a")).exists())
        assertEquals(ChannelMode.STEREO, tracks.first().channelMode)
        assertEquals(null, project.toMultiPlaybackSpec(tracks))
        val selected = tracks.map { it.id }.toSet()
        assertEquals(listOf("take-a", "take-b"), selectedPlayableTracks(tracks, selected).map { it.id })
        val recorded = project.toRecordingSpec(TrackEntity(id = "new-take", projectId = project.id))
        assertEquals(48_000, recorded.sampleRate)
        vm.open("song")
        advanceUntilIdle()
        assertEquals("song", vm.openProject.first())
        assertEquals(2, headers.reads)
        assertEquals("song", dao.observeProject("song").first()?.remoteUrl)
        collect.cancel()
    }

    @Test
    fun openAddsAPublishedTakeWithoutReplacingTracksAlreadyOnThePhone() = runTest {
        val wav = wavBytes(sampleRateHz = 44_100, frames = 100)
        val existing = File.createTempFile("already-here", ".wav")
        existing.writeText("keep")
        val dao = FakeProjectDao(
            projects = listOf(ProjectEntity(id = "song", name = "Song")),
            tracks = listOf(
                TrackEntity(
                    id = "take-a",
                    projectId = "song",
                    name = "Take 1",
                    wavFilePath = existing.absolutePath,
                    duration = 100,
                    contentHash = "hash-a",
                ),
            ),
        )
        val headers = HeaderBytes(wav)
        val local = room(dao, shared = AddedTake(wav.size.toLong()), headers = headers)
        val vm = home(local)
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        vm.open("song")
        advanceUntilIdle()
        assertEquals("song", vm.openProject.first())
        assertEquals(1, headers.reads)
        assertEquals("keep", existing.readText())
        assertEquals("Song", dao.observeProject("song").first()?.name)
        val tracks = dao.observeTracks("song").first()
        assertEquals(listOf("take-a", "take-c"), tracks.map { it.id })
        assertEquals(existing.absolutePath, tracks.first().wavFilePath)
        assertTrue(tracks.last().wavFilePath.isBlank() && tracks.last().streamsSharedTake())
        assertEquals(listOf(0, 1), tracks.map { it.position })
        assertEquals("hash-c", tracks.last().contentHash)
        assertTrue((tracks.last().duration ?: 0L) > 0L)
        collect.cancel()
    }

    @Test
    fun presentDeletesAPartialTakeWhenALaterDownloadFails() = runTest {
        val dao = FakeProjectDao()
        val paths = TempDirAudioFilePathProvider()
        val wav = wavBytes(sampleRateHz = 44_100, frames = 100)
        val local = room(dao, paths, ListedTakes(wav.size.toLong()), FailAfterFirstHeader(wav))
        val failure = runCatching { local.present("song", "Song") }.exceptionOrNull()
        assertTrue(failure is SharedPlaceException)
        assertEquals(null, dao.observeProject("song").first())
        assertFalse(File(paths.trackOutputPath("song", "take-a")).exists())
        assertFalse(File(paths.trackOutputPath("song", "take-b")).exists())
    }

    @Test
    fun openReportsAConnectionProblemAndDoesNotNavigate() = runTest {
        assertOpenStaysPut(UnknownHostException("Unable to resolve host"), R.string.error_no_connection)
    }

    @Test
    fun openKeepsTheCardFailureWhenTheServerRejectsTheTakes() = runTest {
        assertOpenStaysPut(IllegalStateException("share api status 500"), R.string.error_card_not_on_phone)
    }

    private suspend fun TestScope.assertOpenStaysPut(error: Exception, message: Int) {
        val dao = FakeProjectDao()
        val vm = home(room(dao, shared = FailingFiles(error)))
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        val opened = mutableListOf<String>()
        val opens = backgroundScope.launch { vm.openProject.collect { opened += it } }
        advanceUntilIdle()
        vm.open("song")
        advanceUntilIdle()
        assertEquals(message, vm.userMessages.first().resId)
        advanceUntilIdle()
        assertTrue(opened.isEmpty())
        assertEquals(null, dao.observeProject("song").first())
        collect.cancel()
        opens.cancel()
    }

    private fun room(
        dao: FakeProjectDao,
        paths: TempDirAudioFilePathProvider = TempDirAudioFilePathProvider(),
        shared: SharedProjects,
        headers: MixSource = HeaderBytes(ByteArray(0)),
    ) = RoomLocalProjects(
        ProjectRepository(dao, NoopProjectFileStore),
        IdleSessions,
        NoPublish,
        paths,
        shared,
    ).also { it.headerSource = headers }

    private fun wavBytes(sampleRateHz: Int, frames: Int, channelCount: Int = 1): ByteArray {
        val file = File.createTempFile("shared-take", ".wav")
        writeConstantPcm16Wav(file, 1, frames, channelCount = channelCount, sampleRateHz = sampleRateHz)
        return file.readBytes().also { file.delete() }
    }

    private fun home(local: LocalProjectLookup) = CommunityHomeViewModel(
        sessions = MemorySessions(session),
        accounts = UnusedAccounts,
        sharedProjects = FixedProjects(listOf(SharedProject("song", "Song", emptyList(), null, "Ada"))),
        localProjects = local,
        ownedShares = NoRefresh,
    )

    private class ListedTakes(private val size: Long) : SharedProjects {
        override suspend fun list(): List<SharedProject> = error("unused")

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

        override suspend fun files(id: String): List<SharedTake> = listOf(
            SharedTake("take-b", "hash-b", size, "b"),
            SharedTake("take-a", "hash-a", size, "a"),
        )
    }

    private class AddedTake(private val size: Long) : SharedProjects {
        override suspend fun list(): List<SharedProject> = error("unused")

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

        override suspend fun files(id: String): List<SharedTake> = listOf(
            SharedTake("take-a", "hash-a", size, "a"),
            SharedTake("take-c", "hash-c", size, "c"),
        )
    }

    private class FailingFiles(private val error: Exception) : SharedProjects {
        override suspend fun list(): List<SharedProject> = error("unused")

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

        override suspend fun files(id: String): List<SharedTake> = throw error
    }

    private class HeaderBytes(private val wav: ByteArray) : MixSource {
        var reads = 0

        override fun open(url: String, start: Long, endInclusive: Long): InputStream {
            reads += 1
            val from = start.toInt().coerceAtMost(wav.size)
            val to = (endInclusive + 1).toInt().coerceAtMost(wav.size)
            return ByteArrayInputStream(wav.copyOfRange(from, to))
        }
    }

    private class FailAfterFirstHeader(private val wav: ByteArray) : MixSource {
        private var reads = 0

        override fun open(url: String, start: Long, endInclusive: Long): InputStream {
            reads += 1
            if (reads > 1) error("probe failed")
            val from = start.toInt().coerceAtMost(wav.size)
            val to = (endInclusive + 1).toInt().coerceAtMost(wav.size)
            return ByteArrayInputStream(wav.copyOfRange(from, to))
        }
    }

    private class FixedProjects(private val projects: List<SharedProject>) : SharedProjects {
        override suspend fun list(): List<SharedProject> = projects

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

        override suspend fun files(id: String): List<SharedTake> = error("unused")
    }

    private class MemorySessions(session: AccountSession?) : AccountSessionStore {
        private val sessions = MutableStateFlow(session)

        override val state: Flow<AccountSession?> = sessions

        override suspend fun current(): AccountSession? = sessions.value

        override suspend fun save(session: AccountSession) {
            sessions.value = session
        }

        override suspend fun clear() {
            sessions.value = null
        }
    }

    private object IdleSessions : AccountSessionStore {
        override val state: Flow<AccountSession?> = MutableStateFlow(null)

        override suspend fun current(): AccountSession? = null

        override suspend fun save(session: AccountSession) = Unit

        override suspend fun clear() = Unit
    }

    private object UnusedAccounts : CognitoAccounts {
        override suspend fun register(email: String, password: String, name: String) = error("unused")

        override suspend fun confirm(email: String, code: String) = error("unused")

        override suspend fun signIn(email: String, password: String): AccountSession = error("unused")

        override suspend fun refresh(session: AccountSession): AccountSession = error("unused")

        override suspend fun signOut(refreshToken: String) = error("unused")
    }

    private object NoPublish : OwnedProjectPublish {
        override suspend fun publishOwner(projectId: String) = Unit

        override suspend fun publishMix(projectId: String) = Unit

        override suspend fun unpublish(projectId: String): Boolean = false
    }

    private object NoRefresh : OwnedShareRefresh {
        override suspend fun publishOwnerNames() = Unit

        override suspend fun publishMixes() = Unit

        override suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String> = emptySet()
    }

    private companion object {
        val session = AccountSession(
            token = "access-token",
            accountId = "account-1",
            email = "owner@example.com",
            name = "Owner",
            refreshToken = "refresh-token",
        )
    }
}
