package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RecordingSessionControllerTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    private companion object {
        const val PID = "project-1"
    }

    private fun project() = ProjectEntity(id = PID, name = "P")

    @Test
    fun `executeRecordPressed sets recording id and clears startup after successful persist`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val audio = FakeAudioController()
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("engine OK") },
                notifyPersistFailed = { throw AssertionError("persist OK") },
            )

            assertNotNull(sut.recordingTrackId.value)
            assertFalse(sut.recordingStartup.value)
            advanceUntilIdle()
        }

    @Test
    fun `executeRecordPressed clears startup on engine failure`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val audio = FakeAudioController(startRecordingPath = null)
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)
            var notified = false

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { notified = true },
                notifyPersistFailed = { throw AssertionError("unexpected persist notify") },
            )

            assertTrue(notified)
            assertNull(sut.recordingTrackId.value)
            assertNull(sut.optimisticRecordingTrack.value)
            assertFalse(sut.recordingStartup.value)
            advanceUntilIdle()
        }

    @Test
    fun `optimistic recording row is set before native startRecording completes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val audio = FakeAudioController()
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)
            var sawOptimisticBeforeEngineReturn = false
            audio.onEnterStartRecording = {
                assertNotNull(sut.optimisticRecordingTrack.value)
                assertNotNull(sut.recordingTrackId.value)
                assertEquals(sut.recordingTrackId.value, sut.optimisticRecordingTrack.value?.id)
                assertFalse(sut.recordingStartup.value)
                sawOptimisticBeforeEngineReturn = true
            }

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("engine OK") },
                notifyPersistFailed = { throw AssertionError("persist OK") },
            )

            assertTrue(sawOptimisticBeforeEngineReturn)
            advanceUntilIdle()
        }

    @Test
    fun `persistRecordingRow runs after optimistic flows are already populated`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val audio = FakeAudioController()
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)
            lateinit var idAtPersistEntry: String

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { track ->
                    idAtPersistEntry = track.id
                    assertNotNull(sut.recordingTrackId.value)
                    assertEquals(track.id, sut.recordingTrackId.value)
                    assertNotNull(sut.optimisticRecordingTrack.value)
                    repo.upsertTracks(listOf(track))
                },
                notifyEngineStartFailed = { throw AssertionError("engine OK") },
                notifyPersistFailed = { throw AssertionError("persist OK") },
            )

            assertTrue(idAtPersistEntry.isNotEmpty())
            advanceUntilIdle()
        }

    @Test
    fun `executeRecordPressed rolls back and stops recorder when persist fails`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList(), failUpsertTrack = true)
            val audio = FakeAudioController()
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)
            var notified = false

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("unexpected engine notify") },
                notifyPersistFailed = { notified = true },
            )

            assertTrue(notified)
            assertNull(sut.recordingTrackId.value)
            assertNull(sut.optimisticRecordingTrack.value)
            assertFalse(sut.recordingStartup.value)
            assertEquals(1, audio.stopRecordingCalls)
            advanceUntilIdle()
        }
}
