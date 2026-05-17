package com.georgv.audioworkstation.ui.screens.projects

import android.content.Context
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportResult
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.AudioImportTarget
import com.georgv.audioworkstation.core.audio.AudioImporter
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackSpec
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.audio.RecordingSpec
import com.georgv.audioworkstation.data.db.AppDatabase
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-backed tests for reorder/optimistic sync, delete rollback, and rename validation.
 * Complements [ProjectViewModelTest] (fake DAO) with a real DAO + in-memory database.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProjectViewModelRobolectricTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase

    @Before
    fun openDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun closeDb() {
        db.close()
    }

    @Test
    fun optimisticReorder_clearsWhenDbMatchesAndUiTracksDbEdits() = runTest {
        val repo = ProjectRepository(db.projectDao(), NoOpProjectFileStoreForRobolectric())
        runBlocking {
            repo.upsertProject(ProjectEntity(id = "p1", name = "P"))
            repo.upsertTracks(
                listOf(
                    trackEntity("a", "p1", 0),
                    trackEntity("b", "p1", 1),
                    trackEntity("c", "p1", 2),
                )
            )
        }

        val vm = robolectricVm(repo)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        runBlocking { vm.bind("p1") }
        advanceUntilIdle()

        assertEquals(listOf("a", "b", "c"), vm.uiState.value.tracks.map { it.id })

        val tracksSnapshot = vm.uiState.value.tracks
        val reordered = listOf(tracksSnapshot[1], tracksSnapshot[0], tracksSnapshot[2])
        vm.setTrackOrderSession("p1", reordered)
        advanceUntilIdle()
        assertEquals(listOf("b", "a", "c"), vm.uiState.value.tracks.map { it.id })

        vm.persistTrackOrderToDb("p1")
        advanceUntilIdle()

        val fromDb = runBlocking { db.projectDao().observeTracks("p1").first() }
        assertEquals(listOf("b", "a", "c"), fromDb.map { it.id })
        assertEquals(listOf(0, 1, 2), fromDb.map { it.position })

        runBlocking {
            db.projectDao().upsertTrack(fromDb.first { it.id == "b" }.copy(name = "renamed-b"))
        }
        advanceUntilIdle()
        assertEquals("renamed-b", vm.uiState.value.tracks.first { it.id == "b" }.name)
    }

    @Test
    fun deleteTrack_restoresSelectionWhenRepositoryThrows() = runTest {
        val repo = ProjectRepository(db.projectDao(), FailingDeleteTrackFileStoreForRobolectric())
        runBlocking {
            repo.upsertProject(ProjectEntity(id = "p1", name = "P"))
            repo.upsertTracks(
                listOf(
                    trackEntity("t1", "p1", 0),
                    trackEntity("t2", "p1", 1),
                )
            )
        }

        val vm = robolectricVm(repo)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        runBlocking { vm.bind("p1") }
        advanceUntilIdle()

        vm.toggleSelect("t1")
        advanceUntilIdle()
        assertTrue("t1" in vm.uiState.value.selectedTrackIds)

        vm.deleteTrack("t1")
        advanceUntilIdle()

        assertTrue(vm.uiState.value.tracks.any { it.id == "t1" })
        assertTrue("t1" in vm.uiState.value.selectedTrackIds)
    }

    @Test
    fun renameProject_blank_emitsValidationMessage() = runTest {
        val repo = ProjectRepository(db.projectDao(), NoOpProjectFileStoreForRobolectric())
        runBlocking {
            repo.upsertProject(ProjectEntity(id = "p1", name = "My Project"))
        }
        val vm = robolectricVm(repo)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        runBlocking { vm.bind("p1") }
        advanceUntilIdle()

        val pending = async { vm.userMessages.first() }
        vm.renameProject("   ")
        advanceUntilIdle()
        assertEquals(R.string.error_project_name_blank, pending.await().resId)
    }

    @Test
    fun renameProject_tooLong_emitsValidationMessage() = runTest {
        val repo = ProjectRepository(db.projectDao(), NoOpProjectFileStoreForRobolectric())
        runBlocking {
            repo.upsertProject(ProjectEntity(id = "p1", name = "My Project"))
        }
        val vm = robolectricVm(repo)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        runBlocking { vm.bind("p1") }
        advanceUntilIdle()

        val pending = async { vm.userMessages.first() }
        vm.renameProject("x".repeat(41))
        advanceUntilIdle()
        assertEquals(R.string.error_project_name_too_long, pending.await().resId)
    }

    @Test
    fun renameProject_trimSameAsCurrent_isNoOp() = runTest {
        val repo = ProjectRepository(db.projectDao(), NoOpProjectFileStoreForRobolectric())
        runBlocking {
            repo.upsertProject(ProjectEntity(id = "p1", name = "My Project"))
        }
        val vm = robolectricVm(repo)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        runBlocking { vm.bind("p1") }
        advanceUntilIdle()

        val before = runBlocking { repo.observeProject("p1").first()!!.name }
        vm.renameProject("  My Project  ")
        advanceUntilIdle()
        val after = runBlocking { repo.observeProject("p1").first()!!.name }
        assertEquals(before, after)
        assertEquals("My Project", vm.uiState.value.project?.name)
    }

    @Test
    fun renameTrack_blank_emitsValidationMessage() = runTest {
        val repo = ProjectRepository(db.projectDao(), NoOpProjectFileStoreForRobolectric())
        runBlocking {
            repo.upsertProject(ProjectEntity(id = "p1", name = "P"))
            repo.upsertTracks(listOf(trackEntity("tr", "p1", 0, name = "Track")))
        }
        val vm = robolectricVm(repo)
        backgroundScope.launch { vm.uiState.collect { } }
        runCurrent()

        runBlocking { vm.bind("p1") }
        advanceUntilIdle()

        val pending = async { vm.userMessages.first() }
        vm.renameTrack("tr", " \t ")
        advanceUntilIdle()
        assertEquals(R.string.error_track_name_blank, pending.await().resId)
    }

    private fun robolectricVm(
        repo: ProjectRepository,
        audioImporter: AudioImporter = ThrowingAudioImporterForRobolectric(),
        audioFilePathProvider: AudioFilePathProvider = NullablePathAudioFileProvider(null),
    ): ProjectViewModel {
        val audio = NoOpAudioControllerForRobolectric()
        return ProjectViewModel(
            repo,
            audio,
            ProjectAudioImportCoordinator(repo, audioImporter, audioFilePathProvider),
            ProjectRecordingCoordinator(repo, audio),
        )
    }

    private fun trackEntity(
        id: String,
        projectId: String,
        position: Int,
        name: String = id,
    ) = TrackEntity(
        id = id,
        projectId = projectId,
        name = name,
        position = position,
    )
}

private class NoOpAudioControllerForRobolectric : AudioController {
    override val playbackState = MutableStateFlow(false)
    override val recordingInputLevel = MutableStateFlow(0f)
    override fun startRecording(spec: RecordingSpec): String? = null
    override fun stopRecording(): Boolean = true
    override fun startPlayback(spec: PlaybackSpec): Boolean = false
    override fun startPlayback(spec: MultiPlaybackSpec): Boolean = false
    override fun setPlaybackGain(gain: Float) = Unit
    override fun stopPlayback(): Boolean = true
    override fun release() = Unit
}

private class ThrowingAudioImporterForRobolectric : AudioImporter {
    override suspend fun import(
        source: AudioImportSource,
        destinationPath: String,
        target: AudioImportTarget,
    ): AudioImportResult = error("import not used")
}

private class NullablePathAudioFileProvider(
    private val path: String?,
) : AudioFilePathProvider {
    override fun trackOutputPath(projectId: String, trackId: String): String? = path
}

private class NoOpProjectFileStoreForRobolectric : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit
    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

private class FailingDeleteTrackFileStoreForRobolectric : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) {
        throw RuntimeException("simulated deleteTrackFile failure")
    }

    override suspend fun deleteProjectFolder(projectId: String) = Unit
}
