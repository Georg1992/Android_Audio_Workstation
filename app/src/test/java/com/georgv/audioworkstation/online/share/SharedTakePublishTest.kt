package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.core.audio.NoopProjectFileStore
import com.georgv.audioworkstation.data.db.dao.FakeProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpResult
import com.georgv.audioworkstation.online.network.HttpTransport
import java.io.File
import javax.inject.Provider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTakePublishTest {
    @Test
    fun aNewTakeOnASharedProjectIsUploadedAndStored() = runBlocking {
        val file = File.createTempFile("new-take", ".wav")
        file.writeText("abc")
        val dao = FakeProjectDao(
            projects = listOf(ProjectEntity(id = "song", name = "Song", remoteUrl = "song")),
        )
        val api = RecordingApi()
        val objects = RecordingObjects()
        lateinit var publish: HttpSharedTakePublish
        val repo = ProjectRepository(
            dao,
            NoopProjectFileStore,
            sharedTakes = Provider { publish },
        )
        publish = HttpSharedTakePublish(FixedSession(session), IdleAccounts(), repo, api, objects)
        val track = TrackEntity(id = "take-new", projectId = "song", name = "Take 3", wavFilePath = file.path)
        repo.publishRecordedTake(track)
        val stored = dao.observeTracks("song").first().single()
        assertEquals(sha256Hex(file), stored.contentHash)
        assertEquals("Take 3", stored.name)
        assertEquals(
            listOf("/shared-projects/song/uploads", "/shared-projects/song/files"),
            api.paths,
        )
        assertEquals(1, objects.puts)
        repo.publishRecordedTake(stored)
        assertEquals(1, objects.puts)
        assertEquals(2, api.paths.size)
    }

    @Test
    fun aNewTakeOnTheOwnersSharedProjectIsUploaded() = runBlocking {
        val file = File.createTempFile("new-take", ".wav")
        file.writeText("abc")
        val dao = FakeProjectDao(
            projects = listOf(ProjectEntity(id = "song", name = "Song", syncStatus = SyncStatus.SYNCED)),
        )
        val api = RecordingApi()
        val repo = ProjectRepository(dao, NoopProjectFileStore)
        val publish = HttpSharedTakePublish(FixedSession(session), IdleAccounts(), repo, api, RecordingObjects())
        val stored = publish.publishNewTake(
            TrackEntity(id = "take-new", projectId = "song", wavFilePath = file.path),
        )
        assertEquals(sha256Hex(file), stored.contentHash)
        assertEquals(listOf("/shared-projects/song/uploads", "/shared-projects/song/files"), api.paths)
    }

    @Test
    fun aLocalProjectDoesNotUploadANewTake() = runBlocking {
        val file = File.createTempFile("new-take", ".wav")
        file.writeText("abc")
        val dao = FakeProjectDao(projects = listOf(ProjectEntity(id = "song", name = "Song")))
        val api = RecordingApi()
        val objects = RecordingObjects()
        val repo = ProjectRepository(dao, NoopProjectFileStore)
        val publish = HttpSharedTakePublish(FixedSession(session), IdleAccounts(), repo, api, objects)
        val track = TrackEntity(id = "take-new", projectId = "song", wavFilePath = file.path)
        val stored = publish.publishNewTake(track)
        assertEquals(track.contentHash, stored.contentHash)
        assertTrue(api.paths.isEmpty())
        assertEquals(0, objects.puts)
    }

    private class RecordingApi : HttpTransport {
        val paths = ArrayList<String>()

        override fun exchange(call: HttpCall): HttpResult {
            paths += call.path
            val body = if (call.path.endsWith("/uploads")) {
                """{"alreadyStored":false,"uploadUrl":"https://s3.example/put","headers":{"content-type":"audio/wav"}}"""
            } else {
                """{"storageKey":"ok"}"""
            }
            return HttpResult(200, body)
        }
    }

    private class RecordingObjects : ShareObjectTransport {
        var puts = 0

        override fun put(url: String, headers: Map<String, String>, file: File) {
            puts += 1
        }

        override fun download(url: String, destination: File) = error("unused")
    }

    private class FixedSession(private val session: AccountSession) : AccountSessionStore {
        override val state: Flow<AccountSession?> = MutableStateFlow(session)

        override suspend fun current(): AccountSession? = session

        override suspend fun save(session: AccountSession) = Unit

        override suspend fun clear() = Unit
    }

    private class IdleAccounts : CognitoAccounts {
        override suspend fun register(email: String, password: String, name: String) = error("unused")

        override suspend fun confirm(email: String, code: String) = error("unused")

        override suspend fun signIn(email: String, password: String): AccountSession = error("unused")

        override suspend fun refresh(session: AccountSession): AccountSession = error("unused")

        override suspend fun signOut(refreshToken: String) = error("unused")
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
