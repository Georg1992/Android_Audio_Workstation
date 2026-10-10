package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.audio.TrackImportStatus
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.SyncStatus
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpResult
import com.georgv.audioworkstation.online.network.HttpTransport
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShareSelectionTest {
    @Test
    fun sha256OfAbcIsTheKnownDigest() {
        val file = File.createTempFile("share-abc", ".wav")
        file.writeText("abc")
        try {
            assertEquals(ABC_HASH, sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun shareSkipsRecordingMissingAndUnreadyTakes() {
        val ready = File.createTempFile("share-ready", ".wav")
        ready.writeText("abc")
        val missing = File(ready.parentFile, "share-missing-${ready.name}")
        try {
            val selected = tracksToShare(
                listOf(
                    track("recording", ready.path, isRecording = true),
                    track("importing", ready.path, importStatus = TrackImportStatus.IMPORTING),
                    track("blank", ""),
                    track("gone", missing.path),
                    track("ready", ready.path),
                ),
            )
            assertEquals(listOf("ready"), selected.map { it.trackId })
        } finally {
            ready.delete()
        }
    }

    @Test
    fun restoreOnlyListsSharedTakesWhoseFileIsGone() {
        val present = File.createTempFile("share-present", ".wav")
        present.writeText("abc")
        val missing = File(present.parentFile, "share-gone-${present.name}")
        try {
            val selected = tracksToRestore(
                listOf(
                    track("local", missing.path, contentHash = null),
                    track("blank-path", "", contentHash = ABC_HASH),
                    track("still-here", present.path, contentHash = ABC_HASH),
                    track("missing", missing.path, contentHash = ABC_HASH),
                ),
            )
            assertEquals(listOf("missing"), selected.map { it.id })
        } finally {
            present.delete()
        }
    }

    @Test
    fun alreadyStoredShareKeepsTheLocalFileAndDoesNotUpload() = runBlocking {
        val audio = File.createTempFile("share-kept", ".wav")
        audio.writeText("abc")
        val objects = RecordingObjects()
        val dao = MemoryProjectDao(
            project = ProjectEntity(id = "project-a", name = "Kept"),
            tracks = listOf(track("track-a", audio.path)),
        )
        val share = HttpProjectShare(
            sessions = FixedSession(session),
            projects = ProjectRepository(dao, NoopFiles),
            api = ScriptedApi(
                listOf(
                    """{"id":"project-a"}""",
                    """{"storageKey":"$ABC_HASH","alreadyStored":true}""",
                    """{"storageKey":"$ABC_HASH"}""",
                ),
            ),
            objects = objects,
        )
        try {
            share.share("project-a")
            assertEquals(0, objects.puts)
            assertEquals("abc", audio.readText())
            val stored = dao.tracks.value.single()
            assertEquals(ABC_HASH, stored.contentHash)
            assertEquals(SyncStatus.SYNCED, stored.syncStatus)
            assertNull(stored.remoteUrl)
            val project = dao.projects.value.single()
            assertEquals(SyncStatus.SYNCED, project.syncStatus)
            assertEquals("account-1", project.ownerUserId)
            assertNull(project.remoteUrl)
        } finally {
            audio.delete()
        }
    }

    @Test
    fun aNewHashIsUploadedOnceAndTheLocalFileStays() = runBlocking {
        val audio = File.createTempFile("share-new", ".wav")
        audio.writeText("abc")
        val objects = RecordingObjects()
        val dao = MemoryProjectDao(
            project = ProjectEntity(id = "project-a", name = "New"),
            tracks = listOf(track("track-a", audio.path)),
        )
        val share = HttpProjectShare(
            sessions = FixedSession(session),
            projects = ProjectRepository(dao, NoopFiles),
            api = ScriptedApi(
                listOf(
                    """{"id":"project-a"}""",
                    """{"storageKey":"$ABC_HASH","alreadyStored":false,"uploadUrl":"https://example.invalid/put","headers":{"Content-Type":"application/octet-stream"}}""",
                    """{"storageKey":"$ABC_HASH"}""",
                ),
            ),
            objects = objects,
        )
        try {
            share.share("project-a")
            assertEquals(1, objects.puts)
            assertTrue(audio.isFile)
            assertEquals("abc", audio.readText())
        } finally {
            audio.delete()
        }
    }

    @Test
    fun downloadRefusesToReplaceAudioThatIsAlreadyOnThePhone() {
        val audio = File.createTempFile("share-local", ".wav")
        audio.writeText("keep-me")
        try {
            var failed = false
            try {
                UrlConnectionShareObjects().download("http://127.0.0.1:9/audio", audio)
            } catch (error: IllegalStateException) {
                failed = error.message == "local audio already exists"
            }
            assertTrue(failed)
            assertEquals("keep-me", audio.readText())
        } finally {
            audio.delete()
        }
    }

    private fun track(
        id: String,
        path: String,
        isRecording: Boolean = false,
        importStatus: TrackImportStatus = TrackImportStatus.READY,
        contentHash: String? = null,
    ) = TrackEntity(
        id = id,
        projectId = "project-a",
        wavFilePath = path,
        isRecording = isRecording,
        importStatus = importStatus,
        contentHash = contentHash,
    )

    private class RecordingObjects : ShareObjectTransport {
        var puts = 0

        override fun put(url: String, headers: Map<String, String>, file: File) {
            puts += 1
        }

        override fun download(url: String, destination: File) {
            error("download was not requested")
        }
    }

    private class ScriptedApi(private val bodies: List<String>) : HttpTransport {
        private var index = 0

        override fun exchange(call: HttpCall): HttpResult {
            val body = bodies[index]
            index += 1
            return HttpResult(200, body)
        }
    }

    private class FixedSession(private val session: AccountSession) : AccountSessionStore {
        override val state: Flow<AccountSession?> = flowOf(session)

        override suspend fun current(): AccountSession? = session

        override suspend fun save(session: AccountSession) = Unit

        override suspend fun clear() = Unit
    }

    private class MemoryProjectDao(
        project: ProjectEntity,
        tracks: List<TrackEntity>,
    ) : ProjectDao {
        val projects = MutableStateFlow(listOf(project))
        val tracks = MutableStateFlow(tracks)

        override suspend fun insertProject(project: ProjectEntity) {
            projects.value = listOf(project)
        }

        override suspend fun updateProject(project: ProjectEntity) {
            projects.value = listOf(project)
        }

        override fun observeProjects(): Flow<List<ProjectEntity>> = projects

        override fun observeProject(projectId: String): Flow<ProjectEntity?> =
            projects.map { rows -> rows.firstOrNull { it.id == projectId } }

        override suspend fun projectExists(projectId: String): Boolean =
            projects.value.any { it.id == projectId }

        override suspend fun deleteProject(projectId: String) {
            projects.value = emptyList()
        }

        override fun observeTracks(projectId: String): Flow<List<TrackEntity>> = tracks

        override suspend fun upsertTrack(track: TrackEntity) {
            tracks.value = listOf(track)
        }

        override suspend fun upsertTracks(tracks: List<TrackEntity>) {
            this.tracks.value = tracks
        }

        override suspend fun updateTracks(tracks: List<TrackEntity>) {
            this.tracks.value = tracks
        }

        override suspend fun deleteTrack(trackId: String) {
            tracks.value = tracks.value.filterNot { it.id == trackId }
        }
    }

    private object NoopFiles : ProjectFileStore {
        override suspend fun deleteTrackFile(track: TrackEntity) = Unit

        override suspend fun deleteProjectFolder(projectId: String) = Unit
    }

    private companion object {
        const val ABC_HASH = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val session = AccountSession(
            token = "access-token",
            accountId = "account-1",
            email = "owner@example.com",
            name = "Owner",
            refreshToken = "refresh-token",
        )
    }
}
