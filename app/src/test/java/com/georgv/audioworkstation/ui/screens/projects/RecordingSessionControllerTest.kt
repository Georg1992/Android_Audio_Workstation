package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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

    private fun permissiveStoragePrecheck(): suspend (ProjectEntity) -> Boolean = { _ -> true }

    private fun blockedStoragePrecheck(): suspend (ProjectEntity) -> Boolean = { _ -> false }

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
                timelineStartOffsetMs = 0L,
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("engine OK") },
                notifyPersistFailed = { throw AssertionError("persist OK") },
                storagePrecheck = permissiveStoragePrecheck(),
                notifyStorageStartBlocked = { throw AssertionError("storage OK") },
                onPendingTrackAllocated = { true },
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
                timelineStartOffsetMs = 0L,
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { notified = true },
                notifyPersistFailed = { throw AssertionError("unexpected persist notify") },
                storagePrecheck = permissiveStoragePrecheck(),
                notifyStorageStartBlocked = { throw AssertionError("storage OK") },
                onPendingTrackAllocated = { true },
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
                timelineStartOffsetMs = 0L,
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("engine OK") },
                notifyPersistFailed = { throw AssertionError("persist OK") },
                storagePrecheck = permissiveStoragePrecheck(),
                notifyStorageStartBlocked = { throw AssertionError("storage OK") },
                onPendingTrackAllocated = { true },
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
                timelineStartOffsetMs = 0L,
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
                storagePrecheck = permissiveStoragePrecheck(),
                notifyStorageStartBlocked = { throw AssertionError("storage OK") },
                onPendingTrackAllocated = { true },
            )

            assertTrue(idAtPersistEntry.isNotEmpty())
            advanceUntilIdle()
        }

    @Test
    fun `executeRecordPressed persists timelineStartOffsetMs on allocated track`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val audio = FakeAudioController()
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                timelineStartOffsetMs = 30_000L,
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("engine OK") },
                notifyPersistFailed = { throw AssertionError("persist OK") },
                storagePrecheck = permissiveStoragePrecheck(),
                notifyStorageStartBlocked = { throw AssertionError("storage OK") },
                onPendingTrackAllocated = { true },
            )
            advanceUntilIdle()

            assertEquals(30_000L, dao.observeTracks(PID).first().single().timelineStartOffsetMs)
        }

    @Test
    fun `executeRecordPressed rolls back when pending allocation callback rejects`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val audio = FakeAudioController()
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                timelineStartOffsetMs = 0L,
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("unexpected engine notify") },
                notifyPersistFailed = { throw AssertionError("unexpected persist notify") },
                storagePrecheck = permissiveStoragePrecheck(),
                notifyStorageStartBlocked = { throw AssertionError("storage OK") },
                onPendingTrackAllocated = { false },
            )
            advanceUntilIdle()

            assertNull(sut.recordingTrackId.value)
            assertEquals(0, audio.stopRecordingCalls)
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
                timelineStartOffsetMs = 0L,
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("unexpected engine notify") },
                notifyPersistFailed = { notified = true },
                storagePrecheck = permissiveStoragePrecheck(),
                notifyStorageStartBlocked = { throw AssertionError("storage OK") },
                onPendingTrackAllocated = { true },
            )

            assertTrue(notified)
            assertNull(sut.recordingTrackId.value)
            assertNull(sut.optimisticRecordingTrack.value)
            assertFalse(sut.recordingStartup.value)
            assertEquals(1, audio.stopRecordingCalls)
            advanceUntilIdle()
        }

    @Test
    fun `executeRecordPressed blocks before allocation when storage precheck fails`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val audio = FakeAudioController()
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val coord = ProjectRecordingCoordinator(repo, audio)
            val sut = RecordingSessionController(this, audio, coord)
            var notified = false

            sut.executeRecordPressed(
                projectId = PID,
                projectName = "New",
                timelineStartOffsetMs = 0L,
                ensureProject = { _, _ -> project() },
                visibleTrackCount = { 0 },
                persistRecordingRow = { repo.upsertTracks(listOf(it)) },
                notifyEngineStartFailed = { throw AssertionError("unexpected engine notify") },
                notifyPersistFailed = { throw AssertionError("unexpected persist notify") },
                storagePrecheck = blockedStoragePrecheck(),
                notifyStorageStartBlocked = { notified = true },
                onPendingTrackAllocated = { true },
            )

            assertTrue(notified)
            assertNull(sut.recordingTrackId.value)
            assertEquals(0, audio.stopRecordingCalls)
            advanceUntilIdle()
        }
}
