package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImportResult
import com.georgv.audioworkstation.core.audio.AudioImportSource
import com.georgv.audioworkstation.core.audio.AudioImportTarget
import com.georgv.audioworkstation.core.audio.AudioImporter
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackLaneLifecycle
import com.georgv.audioworkstation.core.audio.PlaybackSpec
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.audio.RecordingSpec
import com.georgv.audioworkstation.core.audio.RecordingStorageFsQuery
import com.georgv.audioworkstation.core.audio.RecordingStorageGuard
import com.georgv.audioworkstation.core.audio.TempDirAudioFilePathProvider
import com.georgv.audioworkstation.core.audio.WavPunchSplicer
import com.georgv.audioworkstation.core.audio.testProjectRecordingCoordinator
import com.georgv.audioworkstation.core.audio.writeConstantPcm16Wav
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.components.WavWaveformPeakExtractor
import com.georgv.audioworkstation.ui.components.sessionTimelineEndMsForTracks
import com.georgv.audioworkstation.ui.components.timelinePlayheadPositionMs
import com.georgv.audioworkstation.ui.components.tempWav
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    private val defaultWaveformPeakExtractor: NoOpWaveformPeakExtractor by lazy {
        NoOpWaveformPeakExtractor(mainDispatcherRule.dispatcher)
    }

    @Test
    fun `bind loads tracks ordered by position`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "b", position = 1),
                track(id = "a", position = 0)
            )
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        assertEquals(listOf("a", "b"), vm.uiState.value.tracks.map { it.id })
        collectJob.cancel()
    }

    @Test
    fun `bind to a different project resets recording session`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project(id = PROJECT_ID), project(id = PROJECT_2_ID, name = "P2")),
                tracks = emptyList(),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.recordingTrackId)

        vm.bind(PROJECT_2_ID)
        advanceUntilIdle()
        assertNull(vm.uiState.value.recordingTrackId)
        assertFalse(vm.uiState.value.isRecordingStartup)

        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed rolls back session state when track upsert fails`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList(), failUpsertTrack = true)
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertNull(vm.uiState.value.recordingTrackId)
        assertFalse(vm.uiState.value.isRecordingStartup)
        assertEquals(1, audioController.stopRecordingCalls)
        assertEquals(R.string.error_create_recording_track_failed, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed shows storage message when precheck fails`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val audioController = FakeAudioController()
        val storageQuery =
            object : RecordingStorageFsQuery {
                override fun availableBytes(path: String): Long? = 0L
            }
        val vm =
            createViewModel(
                dao,
                audioController,
                recordingStorageGuard = RecordingStorageGuard(storageQuery),
            )
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertNull(vm.uiState.value.recordingTrackId)
        assertFalse(vm.uiState.value.isRecordingStartup)
        assertEquals(0, audioController.stopRecordingCalls)
        assertEquals(R.string.error_recording_storage_insufficient_start, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `recording input level is exposed in ui state`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        audioController.emitRecordingInputLevel(0.73f)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.recordingTrackId)
        assertEquals(0.73f, vm.uiState.value.recordingInputLevel, 0.0001f)
        collectJob.cancel()
    }

    @Test
    fun `toggleSelect adds and removes selected track`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0)))
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.toggleSelect("a")
        advanceUntilIdle()
        assertEquals(setOf("a"), vm.uiState.value.selectedTrackIds)

        vm.toggleSelect("a")
        advanceUntilIdle()
        assertEquals(emptySet<String>(), vm.uiState.value.selectedTrackIds)
        collectJob.cancel()
    }

    @Test
    fun `onPlayPressed starts playback and requires pause before restart while playing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 30_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            advanceUntilIdle()

            startPlayback(vm)
            assertEquals(setOf("a"), vm.uiState.value.sessionTrackIds)
            assertEquals(TransportPlaybackPhase.Playing, vm.uiState.value.transportPlaybackPhase)

            startPlayback(vm)
            assertEquals(R.string.error_stop_playback_first, vm.userMessages.first().resId)
            assertEquals(0, audioController.stopPlaybackCalls)

            vm.performStopPressed()
            advanceUntilIdle()
            assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
            assertEquals(1, audioController.stopPlaybackCalls)

            startPlayback(vm)
            assertEquals(2, audioController.startPlaybackCalls)
            collectJob.cancel()
        }

    @Test
    fun `onPlayPressed with no selection does not start playback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav"))
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        invokePlayPressed(vm)

        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        assertEquals(0, audioController.startPlaybackCalls)
        collectJob.cancel()
    }

    @Test
    fun `onPlayPressed starts only selected playable project tracks in visible order`() =
        runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0, wavFilePath = "a.wav"),
                track(id = "b", position = 1, wavFilePath = "b.wav"),
                track(id = "c", position = 2, wavFilePath = "c.wav"),
                track(id = "d", position = 3, wavFilePath = "")
            )
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("b")
        vm.toggleSelect("a")
        vm.toggleSelect("d")
        advanceUntilIdle()
        startPlayback(vm)

        assertEquals(setOf("a", "b"), vm.uiState.value.sessionTrackIds)
        assertEquals(1, audioController.startPlaybackCalls)
        assertEquals(listOf("a", "b"), audioController.lastMultiPlaybackSpec?.lanes?.map { it.trackId })
        assertEquals(listOf("a.wav", "b.wav"), audioController.lastMultiPlaybackSpec?.lanes?.map { it.wavFilePath })
        collectJob.cancel()
    }

    @Test
    fun `onPlayPressed with selected track without wav does not start playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(
                    track(id = "a", position = 0, wavFilePath = ""),
                    track(id = "b", position = 1, wavFilePath = "b.wav")
                )
            )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            advanceUntilIdle()

            invokePlayPressed(vm)
            advanceUntilIdle()

            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
            assertEquals(0, audioController.startPlaybackCalls)
            assertEquals(R.string.error_no_audio_for_selected_tracks, vm.userMessages.first().resId)
            collectJob.cancel()
        }

    @Test
    fun `onPlayPressed rejects selection with more than eight playable tracks`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = (1..9).map { index ->
                track(id = "track-$index", position = index - 1, wavFilePath = "track-$index.wav")
            }
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        (1..9).forEach { index -> vm.toggleSelect("track-$index") }
        advanceUntilIdle()

        invokePlayPressed(vm)
        advanceUntilIdle()

        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        assertEquals(0, audioController.startPlaybackCalls)
        assertEquals(R.string.error_playback_failed_to_start, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed requires explicit stop before starting a new take`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        val activeRecordingId = vm.uiState.value.recordingTrackId

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertEquals(activeRecordingId, vm.uiState.value.recordingTrackId)
        assertEquals(0, audioController.stopRecordingCalls)
        assertEquals(R.string.error_stop_recording_to_record, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `onStopPressed clears recording and playing state`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 5_000L)),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()
        startPlayback(vm)
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        vm.onStopPressed()
        advanceUntilIdle()

        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        assertNull(vm.uiState.value.recordingTrackId)
        assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
        assertEquals(0L, vm.uiState.value.playheadPositionMs)
        collectJob.cancel()
    }

    @Test
    fun `renameTrack updates session and persists to dao`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, name = "Old")))
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.renameTrack("a", "New Name")
        advanceUntilIdle()

        assertEquals("New Name", vm.uiState.value.tracks.single().name)
        assertEquals("New Name", dao.observeTracks(PROJECT_ID).first().single().name)
        collectJob.cancel()
    }

    @Test
    fun `renameTrack rejects blank names and emits feedback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, name = "Old")))
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.renameTrack("a", "   ")
        advanceUntilIdle()
        val message = vm.userMessages.first()

        assertEquals("Old", vm.uiState.value.tracks.single().name)
        assertEquals(R.string.error_track_name_blank, message.resId)
        collectJob.cancel()
    }

    @Test
    fun `renameTrack rolls back when persistence fails`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, name = "Old")),
            failUpsertTrack = true
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.renameTrack("a", "New Name")
        advanceUntilIdle()
        val message = vm.userMessages.first()

        assertEquals("Old", vm.uiState.value.tracks.single().name)
        assertEquals("Old", dao.observeTracks(PROJECT_ID).first().single().name)
        assertEquals(R.string.error_rename_track_failed, message.resId)
        collectJob.cancel()
    }

    @Test
    fun `renameProject updates project and persists to dao`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project(name = "Old Project")))
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.renameProject("New Project")
        advanceUntilIdle()

        assertEquals("New Project", vm.uiState.value.project?.name)
        assertEquals("New Project", dao.observeProject(PROJECT_ID).first()?.name)
        collectJob.cancel()
    }

    @Test
    fun `renameProject rejects blank names and emits feedback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project(name = "Old Project")))
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.renameProject("   ")
        advanceUntilIdle()

        assertEquals("Old Project", vm.uiState.value.project?.name)
        assertEquals(R.string.error_project_name_blank, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `renameProject emits error when persistence fails`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project(name = "Old Project")),
            failUpsertProject = true
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.renameProject("New Project")
        advanceUntilIdle()

        assertEquals("Old Project", dao.observeProject(PROJECT_ID).first()?.name)
        assertEquals(R.string.error_rename_project_failed, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `commitTrackGain persists gain to dao`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", gain = 100f))
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.commitTrackGain("a", 42f)
        advanceUntilIdle()

        assertEquals(42f, vm.uiState.value.tracks.single().gain)
        assertEquals(42f, dao.observeTracks(PROJECT_ID).first().single().gain)
        collectJob.cancel()
    }

    @Test
    fun `setTrackGain does not write to dao during live drag`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", gain = 100f))
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.setTrackGain("a", 42f)
        vm.setTrackGain("a", 33f)
        vm.setTrackGain("a", 50f)
        advanceUntilIdle()

        assertEquals(50f, vm.uiState.value.tracks.single().gain)
        // No commit fired yet, DB still holds the original value.
        assertEquals(100f, dao.observeTracks(PROJECT_ID).first().single().gain)
        collectJob.cancel()
    }

    @Test
    fun `playback spec uses latest optimistic gain`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", gain = 100f))
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.setTrackGain("a", 25f)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        startPlayback(vm)

        assertEquals(0.25f, audioController.lastMultiPlaybackSpec?.lanes?.single()?.gain)
        collectJob.cancel()
    }

    @Test
    fun `multi-track playback spec preserves per-track gains`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0, wavFilePath = "a.wav", gain = 100f),
                track(id = "b", position = 1, wavFilePath = "b.wav", gain = 50f)
            )
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.setTrackGain("a", 20f)
        vm.setTrackGain("b", 75f)
        advanceUntilIdle()
        vm.toggleSelect("a")
        vm.toggleSelect("b")
        advanceUntilIdle()

        startPlayback(vm)

        assertEquals(listOf(0.2f, 0.75f), audioController.lastMultiPlaybackSpec?.lanes?.map { it.gain })
        collectJob.cancel()
    }

    @Test
    fun `setTrackGain pushes live gain to active playback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", gain = 100f))
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()
        startPlayback(vm)

        vm.setTrackGain("a", 25f)
        runCurrent()

        assertEquals(0.25f, audioController.lastPlaybackGain)
        stopPlaybackFully(vm)
        collectJob.cancel()
    }

    @Test
    fun `setTrackOrderSession and persistTrackOrderToDb keep reordered positions`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0),
                track(id = "b", position = 1),
                track(id = "c", position = 2)
            )
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        val reordered = listOf(
            vm.uiState.value.tracks[1],
            vm.uiState.value.tracks[2],
            vm.uiState.value.tracks[0]
        )
        vm.setTrackOrderSession(PROJECT_ID, reordered)
        advanceUntilIdle()
        assertEquals(listOf("b", "c", "a"), vm.uiState.value.tracks.map { it.id })

        vm.persistTrackOrderToDb(PROJECT_ID)
        advanceUntilIdle()

        val persisted = dao.observeTracks(PROJECT_ID).first()
        assertEquals(listOf("b", "c", "a"), persisted.map { it.id })
        assertEquals(listOf(0, 1, 2), persisted.map { it.position })
        collectJob.cancel()
    }

    @Test
    fun `setTrackOrderSession swaps two adjacent tracks with updated positions only on those rows`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0),
                track(id = "b", position = 1),
                track(id = "c", position = 2)
            )
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        val before = vm.uiState.value.tracks
        val proposed = listOf(before[1], before[0], before[2])
        vm.setTrackOrderSession(PROJECT_ID, proposed)
        advanceUntilIdle()

        val after = vm.uiState.value.tracks
        assertEquals(listOf("b", "a", "c"), after.map { it.id })
        assertEquals(listOf(0, 1, 2), after.map { it.position })
        assertSame(before[2], after[2])
        collectJob.cancel()
    }

    @Test
    fun `persistTrackOrderToDb keeps local reordered UI while db write is pending`() = runTest(mainDispatcherRule.dispatcher) {
        val updateTracksGate = CompletableDeferred<Unit>()
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0),
                track(id = "b", position = 1),
                track(id = "c", position = 2)
            ),
            updateTracksGate = updateTracksGate
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.setTrackOrderSession(
            PROJECT_ID,
            listOf(
                vm.uiState.value.tracks[1],
                vm.uiState.value.tracks[2],
                vm.uiState.value.tracks[0]
            )
        )
        advanceUntilIdle()
        assertEquals(listOf("b", "c", "a"), vm.uiState.value.tracks.map { it.id })

        vm.persistTrackOrderToDb(PROJECT_ID)
        runCurrent()

        assertEquals(listOf("b", "c", "a"), vm.uiState.value.tracks.map { it.id })

        updateTracksGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf("b", "c", "a"), dao.observeTracks(PROJECT_ID).first().map { it.id })
        assertEquals(listOf("b", "c", "a"), vm.uiState.value.tracks.map { it.id })
        collectJob.cancel()
    }

    @Test
    fun `deleteTrack blocks deleting active recording track`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0),
                track(id = "b", position = 1)
            )
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        val recordingId = vm.uiState.value.recordingTrackId ?: error("expected recording track")
        val trackIdsBeforeDelete = vm.uiState.value.tracks.map { it.id }

        vm.deleteTrack(recordingId)
        advanceUntilIdle()

        assertEquals(recordingId, vm.uiState.value.recordingTrackId)
        assertEquals(trackIdsBeforeDelete, vm.uiState.value.tracks.map { it.id })
        assertEquals(R.string.error_stop_recording_to_delete_track, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `deleteTrack blocks deleting active playback track`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0, wavFilePath = "a.wav"),
                track(id = "b", position = 1)
            )
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        startPlayback(vm)

        vm.deleteTrack("a")
        runCurrent()

        assertEquals(listOf("a", "b"), vm.uiState.value.tracks.map { it.id })
        assertEquals(setOf("a"), vm.uiState.value.sessionTrackIds)
        assertEquals(R.string.error_stop_playback_to_delete_track, vm.userMessages.first().resId)
        stopPlaybackFully(vm)
        collectJob.cancel()
    }

    @Test
    fun `deleteTrack removes inactive track renumbers positions and clears selection`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0),
                track(id = "b", position = 1)
            )
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")

        vm.deleteTrack("a")
        advanceUntilIdle()

        assertEquals(listOf("b"), vm.uiState.value.tracks.map { it.id })
        assertEquals(listOf(0), vm.uiState.value.tracks.map { it.position })
        assertEquals(emptySet<String>(), vm.uiState.value.selectedTrackIds)
        collectJob.cancel()
    }

    @Test
    fun `deleteTrack rolls back when persistence fails`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0),
                track(id = "b", position = 1)
            ),
            failDeleteTrackAndUpdatePositions = true
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        vm.deleteTrack("a")
        advanceUntilIdle()
        val message = vm.userMessages.first()

        assertEquals(listOf("a", "b"), vm.uiState.value.tracks.map { it.id })
        assertEquals(setOf("a"), vm.uiState.value.selectedTrackIds)
        assertEquals(listOf("a", "b"), dao.observeTracks(PROJECT_ID).first().map { it.id })
        assertEquals(R.string.error_delete_track_failed, message.resId)
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed stores timelineStartOffsetMs zero when playhead is at start`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertEquals(0L, dao.observeTracks(PROJECT_ID).first().single().timelineStartOffsetMs)
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed stores timelineStartOffsetMs from playhead at thirty seconds`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 60_000L)),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs
        vm.setPlayheadPositionMs(30_000L, base)
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        val recorded = dao.observeTracks(PROJECT_ID).first().last { it.id != "a" }
        assertEquals(30_000L, recorded.timelineStartOffsetMs)
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed allows recording when playhead is at timeline end`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs
        vm.setPlayheadPositionMs(playheadMsAtFraction(1f, base), base)
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.recordingTrackId)
        assertEquals(2, dao.observeTracks(PROJECT_ID).first().size)
        val appended = dao.observeTracks(PROJECT_ID).first().last { it.id != "a" }
        assertEquals(10_000L, appended.timelineStartOffsetMs)
        collectJob.cancel()
    }

    @Test
    fun `record into existing track at timeline end starts punch recording`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs
        vm.setPlayheadPositionMs(playheadMsAtFraction(1f, base), base)
        advanceUntilIdle()
        vm.toggleRecordTarget("a")
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertEquals("a", vm.uiState.value.recordingTrackId)
        assertEquals(1, dao.observeTracks(PROJECT_ID).first().size)
        assertEquals(10_000L, vm.uiState.value.playheadPositionMs)
        collectJob.cancel()
    }

    @Test
    fun `deselect during playback silences armed lane without restarting session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 30_000L),
                            track(id = "b", position = 1, wavFilePath = "b.wav").copy(duration = 30_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            vm.toggleSelect("b")
            startPlayback(vm)
            assertEquals(setOf("a", "b"), vm.uiState.value.sessionTrackIds)
            assertEquals(1, audioController.startPlaybackCalls)

            vm.toggleSelect("b")
            advanceUntilIdle()

            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(setOf("a", "b"), vm.uiState.value.sessionTrackIds)
            val audibility = audioController.lastArmedLaneAudibility
            requireNotNull(audibility)
            org.junit.Assert.assertTrue(audibility[0])
            org.junit.Assert.assertFalse(audibility[1])

            vm.toggleSelect("b")
            advanceUntilIdle()
            val audibilityRestored = audioController.lastArmedLaneAudibility
            requireNotNull(audibilityRestored)
            org.junit.Assert.assertTrue(audibilityRestored[0])
            org.junit.Assert.assertTrue(audibilityRestored[1])
            collectJob.cancel()
        }

    @Test
    fun `selecting non-armed track during playback starts hot join not new playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 30_000L),
                            track(id = "b", position = 1, wavFilePath = "b.wav").copy(duration = 30_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)

            vm.toggleSelect("b")
            advanceUntilIdle()

            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(1, audioController.beginHotJoinCalls)
            assertEquals("b.wav", audioController.lastHotJoinWavPath)
            assertEquals(listOf("a"), audioController.lastMultiPlaybackSpec?.lanes?.map { it.trackId })
            collectJob.cancel()
        }

    @Test
    fun `hot join during playback loads lane with timeline clip metadata`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav", duration = 30_000L),
                            track(
                                id = "b",
                                position = 1,
                                wavFilePath = "b.wav",
                                duration = 5_000L,
                            ).copy(timelineStartOffsetMs = 10_000L),
                        ),
                )
            val audioController =
                FakeAudioController().apply {
                    hotJoinReturnLaneIndex = 1
                    hotJoinCommitLifecycle = PlaybackLaneLifecycle.Active
                }
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.setPlayheadPositionMs(12_000L, vm.uiState.value.timelineBaseDurationMs)
            vm.toggleSelect("a")
            startPlayback(vm)
            assertEquals(12_000L, audioController.transportPositionMsValue)
            vm.toggleSelect("b")
            advanceUntilIdle()

            assertEquals(1, audioController.beginHotJoinCalls)
            assertEquals(10_000L, audioController.lastHotJoinClipStartMs)
            assertEquals(5_000L, audioController.lastHotJoinClipDurationMs)
            collectJob.cancel()
        }

    @Test
    fun `hot join skipped when transport is past clip end`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(id = "a", position = 0, wavFilePath = "a.wav", duration = 30_000L),
                        track(id = "b", position = 1, wavFilePath = "b.wav", duration = 5_000L)
                            .copy(timelineStartOffsetMs = 10_000L),
                    ),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        val timelineBaseMs = vm.uiState.value.timelineBaseDurationMs
        vm.setPlayheadPositionMs(16_000L, timelineBaseMs)
        vm.toggleSelect("a")
        startPlayback(vm)
        assertEquals(16_000L, audioController.transportPositionMsValue)
        vm.toggleSelect("b")
        advanceUntilIdle()

        assertEquals(0, audioController.beginHotJoinCalls)
        collectJob.cancel()
    }

    @Test
    fun `deselect during play and record does not stop recording or restart playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "backing", position = 0, wavFilePath = "backing.wav", duration = 30_000L),
                            track(id = "extra", position = 1, wavFilePath = "extra.wav", duration = 30_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("backing")
            vm.toggleSelect("extra")
            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(0, audioController.stopPlaybackCalls)

            vm.toggleSelect("backing")
            runCurrent()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(0, audioController.stopPlaybackCalls)
            val audibility = audioController.lastArmedLaneAudibility
            requireNotNull(audibility)
            require(audibility.isNotEmpty())
            org.junit.Assert.assertFalse(audibility[0])
            collectJob.cancel()
        }

    @Test
    fun `record with selected tracks starts overdub playback from playhead`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "backing", position = 0, wavFilePath = "backing.wav", duration = 30_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("backing")
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs
        vm.setPlayheadPositionMs(5_000L, base)
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        val recordingId = vm.uiState.value.recordingTrackId
        assertNotNull(recordingId)
        assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
        assertEquals(setOf("backing"), vm.uiState.value.sessionTrackIds)
        assertEquals(1, audioController.startPlaybackCalls)
        assertEquals(5_000L, audioController.lastMultiPlaybackSpec?.startPositionMs)
        assertEquals(listOf("backing"), audioController.lastMultiPlaybackSpec?.lanes?.map { it.trackId })
        assertFalse(audioController.lastMultiPlaybackSpec?.lanes?.any { it.trackId == recordingId } == true)
        collectJob.cancel()
    }

    @Test
    fun `record without selection does not start overdub playback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "backing", position = 0, wavFilePath = "backing.wav", duration = 10_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.recordingTrackId)
        assertEquals(0, audioController.startPlaybackCalls)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        collectJob.cancel()
    }

    @Test
    fun `recording transport lock blocks play scrub and playhead before recording phase`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "backing", position = 0, wavFilePath = "backing.wav", duration = 10_000L)),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("backing")
            advanceUntilIdle()

            val pendingRow = track(id = "take-1", position = 1, wavFilePath = "")
            vm.seedRecordingSessionForTests(recordingId = "take-1", optimistic = pendingRow, startup = false)
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
            val base = vm.uiState.value.timelineBaseDurationMs
            val playheadBefore = vm.uiState.value.playheadPositionMs

            invokePlayPressed(vm)
            assertEquals(0, audioController.startPlaybackCalls)

            vm.setPlayheadPositionMs(playheadBefore + 4_000L, base)
            vm.setPlayheadPositionMs(playheadMsAtFraction(0.75f, base), base)
            assertEquals(playheadBefore, vm.uiState.value.playheadPositionMs)
            collectJob.cancel()
        }

    @Test
    fun `recording transport lock blocks playhead while startup is in flight`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.seedRecordingSessionForTests(recordingId = null, optimistic = null, startup = true)
        advanceUntilIdle()

        assertTrue(vm.uiState.value.isRecordingStartup)
        val base = vm.uiState.value.timelineBaseDurationMs
        val playheadBefore = vm.uiState.value.playheadPositionMs

        vm.setPlayheadPositionMs(3_000L, base)
        assertEquals(playheadBefore, vm.uiState.value.playheadPositionMs)
        collectJob.cancel()
    }

    @Test
    fun `overdub session end uses active backing set not full project timeline`() = runTest(mainDispatcherRule.dispatcher) {
        val longTrack =
            track(id = "long", position = 0, wavFilePath = "long.wav", duration = 120_000L)
        val backing =
            track(id = "backing", position = 1, wavFilePath = "backing.wav", duration = 5_000L)
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(longTrack, backing))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("backing")
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertEquals(
            sessionTimelineEndMsForTracks(listOf(backing)),
            audioController.lastMultiPlaybackSpec?.sessionTimelineEndMs,
        )
        assertEquals(5_000L, audioController.lastMultiPlaybackSpec?.sessionTimelineEndMs)
        assertTrue(vm.uiState.value.timelineBaseDurationMs >= 120_000L)
        audioController.completePlayback()
        vm.cancelPlaybackCompletionMonitorForTests()
        collectJob.cancel()
    }

    @Test
    fun `overdub backing playback completes at session end while recording continues`() =
        runTest(mainDispatcherRule.dispatcher) {
            val longTrack =
                track(id = "long", position = 0, wavFilePath = "long.wav", duration = 120_000L)
            val backing =
                track(id = "backing", position = 1, wavFilePath = "backing.wav", duration = 5_000L)
            val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(longTrack, backing))
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("backing")
            advanceUntilIdle()

            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()

            assertEquals(5_000L, audioController.lastMultiPlaybackSpec?.sessionTimelineEndMs)
            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(setOf("backing"), vm.uiState.value.sessionTrackIds)

            audioController.completePlayback()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
            audioController.completePlayback()
            collectJob.cancel()
        }

    @Test
    fun `playhead advances during play and record`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 60_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()
        vm.setPlayheadNativePollEnabledForTests(false)
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        val startMs = vm.uiState.value.playheadPositionMs

        vm.advancePlayheadNativeTransportForTests(startMs + 3_000L)
        advanceUntilIdle()

        assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
        assertEquals(startMs + 3_000L, vm.uiState.value.playheadPositionMs)
        assertTrue(vm.uiState.value.timelineBaseDurationMs >= startMs + 3_000L)
        collectJob.cancel()
    }

    @Test
    fun `overdub playback failure aborts combined transport`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L)),
            )
        val audioController = FakeAudioController(startPlaybackResult = false)
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertNull(vm.uiState.value.recordingTrackId)
        assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
        assertEquals(0L, vm.uiState.value.playheadPositionMs)
        assertEquals(R.string.error_playback_failed_to_start, vm.userMessages.first().resId)
        assertEquals(0, audioController.stopRecordingCalls)
        collectJob.cancel()
    }

    @Test
    fun `recording engine failure stops overdub playback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L)),
            )
        val audioController = FakeAudioController(startRecordingPath = null)
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertNull(vm.uiState.value.recordingTrackId)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        assertEquals(1, audioController.startPlaybackCalls)
        assertEquals(1, audioController.stopPlaybackCalls)
        assertEquals(R.string.error_recording_failed_to_start, vm.userMessages.first().resId)
        collectJob.cancel()
    }

    @Test
    fun `stop during play and record stops playback and recording`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        vm.performStopPressed()
        advanceUntilIdle()

        assertEquals(1, audioController.stopRecordingCalls)
        assertEquals(1, audioController.stopPlaybackCalls)
        assertNull(vm.uiState.value.recordingTrackId)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        assertEquals(0L, vm.uiState.value.playheadPositionMs)
        collectJob.cancel()
    }

    @Test
    fun `recording expands shared timeline when playhead moves past prior end`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L)),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.setPlayheadNativePollEnabledForTests(false)
        assertEquals(10_000L, vm.uiState.value.timelineBaseDurationMs)

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        vm.advancePlayheadNativeTransportForTests(15_000L)
        advanceUntilIdle()

        assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
        assertEquals(15_000L, vm.uiState.value.playheadPositionMs)
        assertEquals(10_000L, vm.uiState.value.timelineBaseDurationMs)
        assertEquals(15_000L, vm.uiState.value.timelineVisibleDurationMs)
        val recordingClip = vm.uiState.value.timelineClipsByTrackId[vm.uiState.value.recordingTrackId]
        assertNotNull(recordingClip)
        assertTrue(recordingClip!!.isActiveRecording)
        assertTrue(recordingClip.isTimelineBase)
        collectJob.cancel()
    }

    @Test
    fun `stop recording resets playhead and keeps expanded timeline`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.setPlayheadNativePollEnabledForTests(false)

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        vm.advancePlayheadNativeTransportForTests(8_000L)
        advanceUntilIdle()

        vm.performStopPressed()
        advanceUntilIdle()

        assertEquals(0L, vm.uiState.value.playheadPositionMs)
        assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
        assertNull(vm.uiState.value.recordingTrackId)
        val saved = dao.observeTracks(PROJECT_ID).first().single()
        assertEquals(0L, saved.timelineStartOffsetMs)
        assertFalse(saved.isRecording)
        assertNotNull(saved.duration)
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed creates missing project with provided name and default track settings`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao()
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID, "QuickRec_2026-03-27_10-00")
        advanceUntilIdle()

        val project = vm.uiState.value.project
        val track = vm.uiState.value.tracks.single()

        assertEquals("QuickRec_2026-03-27_10-00", project?.name)
        assertEquals(44_100, project?.sampleRate)
        assertEquals(16, project?.fileBitDepth)
        assertEquals(track.id, vm.uiState.value.recordingTrackId)
        assertEquals(ChannelMode.MONO, track.channelMode)
        assertEquals("Take 1", track.name)
        assertEquals("recordings/$PROJECT_ID/${track.id}.wav", track.wavFilePath)
        collectJob.cancel()
    }

    @Test
    fun `onCleared stops active transport`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.recordingTrackId)
        assertEquals(setOf("a"), vm.sessionTrackIdsForTests())

        val onCleared = vm.javaClass.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(vm)
        advanceUntilIdle()
        assertEquals(1, audioController.stopRecordingCalls)
        assertEquals(1, audioController.stopPlaybackCalls)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        assertNull(vm.uiState.value.recordingTrackId)
        assertFalse(vm.uiState.value.isRecordingStartup)
        assertEquals(1, audioController.releaseCalls)
        collectJob.cancel()
    }

    @Test
    fun `toggleTrackLoop flips loop flag and persists to dao`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0))
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.toggleTrackLoop("a")
        advanceUntilIdle()

        assertEquals(true, vm.uiState.value.tracks.single().isLoop)
        assertEquals(true, dao.observeTracks(PROJECT_ID).first().single().isLoop)

        vm.toggleTrackLoop("a")
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.tracks.single().isLoop)
        assertEquals(false, dao.observeTracks(PROJECT_ID).first().single().isLoop)
        collectJob.cancel()
    }

    @Test
    fun `toggleTrackLoop is ignored while playback is active`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav"))
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()
        startPlayback(vm)

        vm.toggleTrackLoop("a")
        advanceUntilIdle()

        assertEquals(false, vm.uiState.value.tracks.single().isLoop)
        assertEquals(false, dao.observeTracks(PROJECT_ID).first().single().isLoop)
        stopPlaybackFully(vm)
        collectJob.cancel()
    }

    @Test
    fun `track menu actions are disabled while playback is active`() {
        assertEquals(false, trackActionsEnabled(playbackActive = true))
    }

    @Test
    fun `track menu actions are enabled when playback is inactive`() {
        assertEquals(true, trackActionsEnabled(playbackActive = false))
    }

    @Test
    fun `playback monitor does not restart when looping track native completes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleTrackLoop("a")
            advanceUntilIdle()
            vm.toggleSelect("a")
            advanceUntilIdle()

            startPlayback(vm)
            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(setOf("a"), vm.uiState.value.sessionTrackIds)
            assertTrue(audioController.lastMultiPlaybackSpec!!.lanes.single().loopEnabled)
            assertEquals(0L, audioController.lastMultiPlaybackSpec!!.sessionTimelineEndMs)

            audioController.completePlayback()
            advanceUntilIdle()

            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
            assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)

            collectJob.cancel()
        }

    @Test
    fun `loop native completion clears session without second start attempt`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleTrackLoop("a")
            advanceUntilIdle()
            vm.toggleSelect("a")
            advanceUntilIdle()

            startPlayback(vm)
            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(setOf("a"), vm.uiState.value.sessionTrackIds)

            audioController.completePlayback()
            advanceUntilIdle()

            assertEquals(1, audioController.startPlaybackCalls)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)

            collectJob.cancel()
        }

    @Test
    fun `importAudio persists imported track with suggested name and flag`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val importer = FakeAudioImporter(
            result = AudioImportResult.Success(
                durationMs = 2_500L,
                channelMode = ChannelMode.STEREO,
                channelCount = 2,
            )
        )
        val vm = createViewModel(dao, audioImporter = importer)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        val source = AudioImportSource { null }
        vm.importAudio(PROJECT_ID, source, suggestedName = "My Loop")
        advanceUntilIdle()

        assertEquals(1, importer.importCalls)
        val imported = dao.observeTracks(PROJECT_ID).first().single()
        assertEquals("My Loop", imported.name)
        assertEquals(true, imported.isImported)
        assertEquals(2_500L, imported.duration)
        assertEquals(ChannelMode.STEREO, imported.channelMode)
        assertEquals(2, imported.channelCount)
        assertNotNull(imported.wavFilePath)
        collectJob.cancel()
    }

    @Test
    fun `importAudio generates waveform peaks for imported wav`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val importer = FakeAudioImporter(
            result = AudioImportResult.Success(
                durationMs = 1_000L,
                channelMode = ChannelMode.MONO,
                channelCount = 1,
            ),
            wavSamplesToWrite = shortArrayOf(0, 2_000, 8_000, 16_000, 24_000, 30_000)
        )
        val provider = FakeAudioFilePathProvider(tempDir().absolutePath)
        val vm =
            createViewModel(
                dao,
                audioImporter = importer,
                audioFilePathProvider = provider,
                waveformPeakExtractor = WavWaveformPeakExtractor(ioDispatcher = mainDispatcherRule.dispatcher),
            )
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.importAudio(PROJECT_ID, AudioImportSource { null }, suggestedName = "Imported")
        advanceUntilIdle()

        val imported = vm.uiState.value.tracks.single()
        assertTrue(vm.uiState.value.waveformStatesByTrackId[imported.id] is WaveformState.Ready)
        val peaks = (vm.uiState.value.waveformStatesByTrackId[imported.id] as? WaveformState.Ready)?.peaks
        assertNotNull(peaks)
        assertEquals(1f, peaks?.amplitudes?.maxOrNull() ?: 0f, 0.0001f)
        collectJob.cancel()
    }

    @Test
    fun `importAudio uses default take name when suggested name is blank`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val importer = FakeAudioImporter()
        val vm = createViewModel(dao, audioImporter = importer)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.importAudio(PROJECT_ID, AudioImportSource { null }, suggestedName = "  ")
        advanceUntilIdle()

        val imported = dao.observeTracks(PROJECT_ID).first().single()
        assertEquals("Take 1 (imported)", imported.name)
        collectJob.cancel()
    }

    @Test
    fun `importAudio surfaces failure message and does not insert track`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val importer = FakeAudioImporter(
            result = AudioImportResult.Failure.SampleRateMismatch(expected = 48_000, actual = 44_100)
        )
        val vm = createViewModel(dao, audioImporter = importer)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.importAudio(PROJECT_ID, AudioImportSource { null }, suggestedName = "bad.wav")
        advanceUntilIdle()

        assertEquals(0, dao.observeTracks(PROJECT_ID).first().size)
        val message = vm.userMessages.first()
        assertEquals(R.string.import_failure_sample_rate_mismatch, message.resId)
        assertEquals(listOf<Any>(44_100, 48_000), message.args)
        collectJob.cancel()
    }

    @Test
    fun `importAudio is blocked while recording`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val audioController = FakeAudioController()
        val importer = FakeAudioImporter()
        val vm = createViewModel(dao, audioController = audioController, audioImporter = importer)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        vm.importAudio(PROJECT_ID, AudioImportSource { null }, suggestedName = "x")
        advanceUntilIdle()

        assertEquals(0, importer.importCalls)
        val message = vm.userMessages.first()
        assertEquals(R.string.error_stop_recording_to_import, message.resId)
        vm.onStopPressed()
        runCurrent()
        collectJob.cancel()
    }

    @Test
    fun `ProjectAudioImportCoordinator returns StorageUnavailable without importing`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val repo = ProjectRepository(dao, NoopProjectFileStore)
        val importer = FakeAudioImporter()
        val coordinator =
            ProjectAudioImportCoordinator(repo, importer, NullTrackOutputPathProvider)
        val outcome =
            coordinator.run(
                projectId = PROJECT_ID,
                project = project(),
                visibleTrackCount = 0,
                source = AudioImportSource { null },
                suggestedName = "x",
            )
        assertEquals(ProjectAudioImportOutcome.StorageUnavailable, outcome)
        assertEquals(0, importer.importCalls)
    }

    @Test
    fun `ProjectAudioImportCoordinator maps success to ReadyToPersist`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val repo = ProjectRepository(dao, NoopProjectFileStore)
        val importer =
            FakeAudioImporter(
                result =
                    AudioImportResult.Success(
                        durationMs = 100L,
                        channelMode = ChannelMode.MONO,
                        channelCount = 1,
                    )
            )
        val coordinator =
            ProjectAudioImportCoordinator(repo, importer, FakeAudioFilePathProvider())
        val outcome =
            coordinator.run(
                projectId = PROJECT_ID,
                project = project(),
                visibleTrackCount = 2,
                source = AudioImportSource { null },
                suggestedName = null,
            )
        assertTrue(outcome is ProjectAudioImportOutcome.ReadyToPersist)
        val track = (outcome as ProjectAudioImportOutcome.ReadyToPersist).importedTrack
        assertEquals("Take 3 (imported)", track.name)
        assertEquals(true, track.isImported)
        assertEquals(100L, track.duration)
        assertEquals(1, track.channelCount)
    }

    @Test
    fun `ProjectRecordingCoordinator returns EngineStartFailed when startRecording yields null`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
            val repo = ProjectRepository(dao, NoopProjectFileStore)
            val audio =
                FakeAudioController(
                    startRecordingPath = null,
                )
            val coord = testProjectRecordingCoordinator(repo, audio)
            val outcome =
                coord.beginRecording(
                    projectId = PROJECT_ID,
                    project = project(),
                    visibleTrackCount = 0,
                )
            assertEquals(RecordingStartOutcome.EngineStartFailed, outcome)
        }

    @Test
    fun `playback monitor clears playing state when native playback completes`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        startPlayback(vm)
        assertEquals(setOf("a"), vm.uiState.value.sessionTrackIds)

        audioController.completePlayback()
        advanceUntilIdle()

        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        collectJob.cancel()
    }

    @Test
    fun `transport stop during playback only invokes stopPlayback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()
        startPlayback(vm)

        vm.onStopPressed()
        advanceUntilIdle()

        assertEquals(0, audioController.stopRecordingCalls)
        assertEquals(1, audioController.stopPlaybackCalls)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
        assertNull(vm.uiState.value.recordingTrackId)
        collectJob.cancel()
    }

    @Test
    fun `transport stop during recording only invokes stopRecording not stopPlayback`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        assertNotNull(vm.uiState.value.recordingTrackId)

        vm.onStopPressed()
        runCurrent()

        assertEquals(1, audioController.stopRecordingCalls)
        assertEquals(0, audioController.stopPlaybackCalls)
        assertNull(vm.uiState.value.recordingTrackId)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
        collectJob.cancel()
    }

    @Test
    fun `recording finalization generates waveform peaks for recorded wav`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = emptyList())
        val audioController = FakeAudioController(startRecordingPath = "${tempDir().absolutePath}/default.wav")
        val vm =
            createViewModel(
                dao,
                audioController,
                waveformPeakExtractor =
                    WavWaveformPeakExtractor(ioDispatcher = mainDispatcherRule.dispatcher),
            )
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        val recordingTrack = vm.uiState.value.tracks.single()
        tempWav(shortArrayOf(0, 1_000, 12_000, 26_000))
            .copyTo(File(recordingTrack.wavFilePath), overwrite = true)
        vm.onStopPressed()
        waitUntil { vm.uiState.value.waveformStatesByTrackId[recordingTrack.id] is WaveformState.Ready }
        advanceUntilIdle()

        assertFalse(vm.uiState.value.tracks.single().isRecording)
        assertNotNull(vm.uiState.value.waveformStatesByTrackId[recordingTrack.id])
        collectJob.cancel()
    }

    @Test
    fun `transport stop with loop active does not process further completions`() = runTest(mainDispatcherRule.dispatcher) {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")),
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleTrackLoop("a")
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        startPlayback(vm)
        assertEquals(1, audioController.startPlaybackCalls)

        audioController.completePlayback()
        advanceUntilIdle()
        assertEquals(1, audioController.startPlaybackCalls)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)

        stopPlaybackFully(vm)
        assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)

        audioController.completePlayback()
        advanceUntilIdle()

        assertEquals(1, audioController.startPlaybackCalls)
        collectJob.cancel()
    }

    @Test
    fun `toggleRecordTarget enforces single selection`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(id = "a", position = 0),
                        track(id = "b", position = 1),
                    ),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.toggleRecordTarget("a")
        advanceUntilIdle()
        assertEquals("a", vm.uiState.value.recordTargetTrackId)

        vm.toggleRecordTarget("b")
        advanceUntilIdle()
        assertEquals("b", vm.uiState.value.recordTargetTrackId)

        vm.toggleRecordTarget("b")
        advanceUntilIdle()
        assertNull(vm.uiState.value.recordTargetTrackId)

        collectJob.cancel()
    }

    @Test
    fun `enabling loop on record target clears record target`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0)),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.toggleRecordTarget("a")
        advanceUntilIdle()
        assertEquals("a", vm.uiState.value.recordTargetTrackId)

        vm.toggleTrackLoop("a")
        advanceUntilIdle()

        assertNull(vm.uiState.value.recordTargetTrackId)
        assertTrue(vm.uiState.value.tracks.single().isLoop)
        collectJob.cancel()
    }

    @Test
    fun `enabling record target on looped track disables loop`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(
                            id = "a",
                            position = 0,
                            isLoop = true,
                            loopStartMs = 1_000L,
                            loopEndMs = 5_000L,
                        ),
                    ),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.toggleRecordTarget("a")
        advanceUntilIdle()

        assertEquals("a", vm.uiState.value.recordTargetTrackId)
        assertFalse(vm.uiState.value.tracks.single().isLoop)
        assertEquals(1_000L, dao.observeTracks(PROJECT_ID).first().single().loopStartMs)
        assertEquals(5_000L, dao.observeTracks(PROJECT_ID).first().single().loopEndMs)
        collectJob.cancel()
    }

    @Test
    fun `updateTrackLoopRegion persists clamped region`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(
                            id = "a",
                            position = 0,
                            duration = 20_000L,
                            isLoop = true,
                        ),
                    ),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.updateTrackLoopRegion("a", loopStartMs = 6_000L, loopEndMs = 12_000L)
        advanceUntilIdle()

        val persisted = dao.observeTracks(PROJECT_ID).first().single()
        assertEquals(6_000L, persisted.loopStartMs)
        assertEquals(12_000L, persisted.loopEndMs)
        collectJob.cancel()
    }

    @Test
    fun `disabling loop keeps loop region then re-enable restores it`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(
                            id = "a",
                            position = 0,
                            duration = 20_000L,
                            isLoop = true,
                            loopStartMs = 6_000L,
                            loopEndMs = 12_000L,
                        ),
                    ),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.toggleTrackLoop("a")
        advanceUntilIdle()
        assertFalse(vm.uiState.value.tracks.single().isLoop)
        assertEquals(6_000L, vm.uiState.value.tracks.single().loopStartMs)
        assertEquals(12_000L, vm.uiState.value.tracks.single().loopEndMs)

        vm.toggleTrackLoop("a")
        advanceUntilIdle()
        assertTrue(vm.uiState.value.tracks.single().isLoop)
        assertEquals(6_000L, vm.uiState.value.tracks.single().loopStartMs)
        assertEquals(12_000L, vm.uiState.value.tracks.single().loopEndMs)
        collectJob.cancel()
    }

    @Test
    fun `recording with record target reuses existing track`() = runTest(mainDispatcherRule.dispatcher) {
        val existing =
            track(
                id = "a",
                position = 0,
                wavFilePath = "${tempDir().absolutePath}/old.wav",
                duration = 5_000L,
            )
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(existing))
        val audioController = FakeAudioController(startRecordingPath = "${tempDir().absolutePath}/rec.wav")
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleRecordTarget("a")
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.tracks.size)
        assertEquals("a", vm.uiState.value.recordingTrackId)
        assertEquals("a", vm.uiState.value.tracks.single().id)
        assertTrue(vm.uiState.value.tracks.single().isRecording)

        vm.onStopPressed()
        advanceUntilIdle()

        assertEquals(1, vm.uiState.value.tracks.size)
        assertEquals("a", vm.uiState.value.tracks.single().id)
        assertEquals(1, dao.observeTracks(PROJECT_ID).first().size)
        collectJob.cancel()
    }

    @Test
    fun `record into existing track keeps visual playhead at current timeline position`() =
        runTest(mainDispatcherRule.dispatcher) {
            val existing =
                track(
                    id = "a",
                    position = 0,
                    wavFilePath = "${tempDir().absolutePath}/old.wav",
                    duration = 60_000L,
                )
            val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(existing))
            val audioController = FakeAudioController(startRecordingPath = "${tempDir().absolutePath}/rec.wav")
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.setPlayheadPositionMs(5_000L, base)
            advanceUntilIdle()
            vm.toggleRecordTarget("a")
            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()

            assertEquals(5_000L, vm.uiState.value.playheadPositionMs)
            assertEquals(5_000L, audioController.lastRecordingSpec?.timelineStartOffsetMs)
            assertEquals(0L, vm.uiState.value.tracks.single().timelineStartOffsetMs)
            assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
            collectJob.cancel()
        }

    @Test
    fun `playback spec after punch finalize uses same wav path and updated duration`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dir = tempDir()
            val paths = TempDirAudioFilePathProvider(dir)
            val wavPath = paths.trackOutputPath(PROJECT_ID, "a")
            writeConstantPcm16Wav(
                file = File(wavPath),
                sampleValue = 1_000,
                frameCount = 44_100 * 20,
                sampleRateHz = 44_100,
            )
            val existing =
                track(
                    id = "a",
                    position = 0,
                    wavFilePath = wavPath,
                    duration = 20_000L,
                )
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(existing),
                )
            val audioController =
                FakeAudioController(
                    startRecordingPath = paths.trackRecordingTempPath(PROJECT_ID, "a"),
                ).apply {
                    onEnterStartRecording = {
                        writeConstantPcm16Wav(
                            file = File(paths.trackRecordingTempPath(PROJECT_ID, "a")),
                            sampleValue = 2_000,
                            frameCount = 44_100 * 5,
                            sampleRateHz = 44_100,
                        )
                    }
                }
            val vm = createViewModel(dao, audioController, audioFilePathProvider = paths)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.setPlayheadPositionMs(18_000L, base)
            advanceUntilIdle()
            vm.toggleRecordTarget("a")
            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()
            vm.onStopPressed()
            for (attempt in 0 until 100) {
                advanceUntilIdle()
                if (dao.observeTracks(PROJECT_ID).first().single().duration == 23_000L) break
            }
            assertEquals(23_000L, dao.observeTracks(PROJECT_ID).first().single().duration)

            vm.toggleSelect("a")
            startPlayback(vm)
            advanceUntilIdle()

            val lane = audioController.lastMultiPlaybackSpec?.lanes?.single()
            assertNotNull(lane)
            assertEquals(wavPath, lane?.wavFilePath)
            assertEquals(23_000L, lane?.timelineClipDurationMs)
            collectJob.cancel()
        }

    @Test
    fun `recording without record target still creates new track`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        assertEquals(2, vm.uiState.value.tracks.size)
        assertNotNull(vm.uiState.value.recordingTrackId)
        assertEquals("a", vm.uiState.value.tracks.first().id)
        collectJob.cancel()
    }

    private suspend fun TestScope.waitUntil(predicate: () -> Boolean) {
        repeat(500) {
            if (predicate()) return
            runCurrent()
        }
        error("waitUntil timed out after 500 iterations")
    }

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "aaw-test-${System.nanoTime()}").apply {
            mkdirs()
            deleteOnExit()
        }

    private fun createViewModel(
        dao: FakeProjectDao,
        audioController: AudioController = FakeAudioController(),
        audioImporter: AudioImporter = FakeAudioImporter(),
        audioFilePathProvider: AudioFilePathProvider = FakeAudioFilePathProvider(),
        recordingStorageGuard: RecordingStorageGuard = permissiveRecordingStorageGuard(),
        waveformPeakExtractor: WavWaveformPeakExtractor = defaultWaveformPeakExtractor,
    ): ProjectViewModel {
        val repo = ProjectRepository(dao, NoopProjectFileStore)
        val audioImportCoordinator =
            ProjectAudioImportCoordinator(repo, audioImporter, audioFilePathProvider)
        val recordingCoordinator =
            ProjectRecordingCoordinator(
                repo,
                audioController,
                audioFilePathProvider,
                WavPunchSplicer(),
            )
        return ProjectViewModel(
            repo,
            audioController,
            audioImportCoordinator,
            recordingCoordinator,
            waveformPeakExtractor,
            audioFilePathProvider,
            recordingStorageGuard,
        ).also {
            it.setPlayheadNativePollEnabledForTests(false)
            it.setRecordingStorageMonitorEnabledForTests(false)
        }
    }

    private fun permissiveRecordingStorageGuard(): RecordingStorageGuard =
        RecordingStorageGuard(
            object : RecordingStorageFsQuery {
                override fun availableBytes(path: String): Long? = Long.MAX_VALUE
            },
        )

    private suspend fun TestScope.stopPlaybackFully(vm: ProjectViewModel) {
        vm.performStopPressed()
        advanceUntilIdle()
        if (vm.uiState.value.transportPlaybackPhase == TransportPlaybackPhase.Paused) {
            vm.performStopPressed()
            advanceUntilIdle()
        }
    }

    private suspend fun TestScope.invokePlayPressed(vm: ProjectViewModel) {
        vm.performPlayPressed()
    }

    private suspend fun TestScope.startPlayback(vm: ProjectViewModel) {
        vm.performPlayPressed()
        // runCurrent only: advanceUntilIdle never completes while native poll delay(16) is active.
        runCurrent()
        check(vm.sessionTrackIdsForTests().isNotEmpty()) {
            "expected playback session after performPlayPressed"
        }
    }

    @Test
    fun `setPlayheadPositionMs clamps to timeline duration`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(id = "a", position = 0, wavFilePath = "/a.wav").copy(duration = 5_000L),
                        track(id = "b", position = 1, wavFilePath = "/b.wav").copy(duration = 10_000L),
                    ),
            )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        val baseDurationMs = vm.uiState.value.timelineBaseDurationMs
        assertEquals(10_000L, baseDurationMs)

        vm.setPlayheadPositionMs(playheadMsAtFraction(0f, baseDurationMs), baseDurationMs)
        advanceUntilIdle()
        assertEquals(0L, vm.uiState.value.playheadPositionMs)

        vm.setPlayheadPositionMs(playheadMsAtFraction(1f, baseDurationMs), baseDurationMs)
        advanceUntilIdle()
        assertEquals(10_000L, vm.uiState.value.playheadPositionMs)

        vm.setPlayheadPositionMs(playheadMsAtFraction(2f, baseDurationMs), baseDurationMs)
        advanceUntilIdle()
        assertEquals(10_000L, vm.uiState.value.playheadPositionMs)

        collectJob.cancel()
    }

    @Test
    fun `play starts from current playhead and follows native transport`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(id = "a", position = 0, wavFilePath = "a.wav", duration = 30_000L),
                    ),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs
        assertEquals(30_000L, base)
        vm.setPlayheadPositionMs(playheadMsAtFraction(0.5f, base), base)
        advanceUntilIdle()
        val startMs = vm.uiState.value.playheadPositionMs
        assertEquals(15_000L, startMs)

        startPlayback(vm)
        assertEquals(startMs, vm.uiState.value.playheadPositionMs)
        assertEquals(startMs, audioController.lastMultiPlaybackSpec?.startPositionMs)
        vm.cancelPlaybackCompletionMonitorForTests()

        audioController.transportPositionMsValue = startMs + 200
        vm.advancePlayheadNativeTransportForTests(startMs + 200)
        advanceUntilIdle()
        assertEquals(startMs + 200, vm.uiState.value.playheadPositionMs)
        collectJob.cancel()
    }

    @Test
    fun `play at zero passes zero start position to audio layer`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 5_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        startPlayback(vm)

        assertEquals(0L, audioController.lastMultiPlaybackSpec?.startPositionMs)
        collectJob.cancel()
    }

    @Test
    fun `play at 1000ms passes start position into multi playback spec`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 5_000L),
                        track(id = "b", position = 1, wavFilePath = "b.wav").copy(duration = 5_000L),
                    ),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        vm.toggleSelect("b")
        vm.setPlayheadPositionMs(1_000L, 5_000L)
        advanceUntilIdle()
        startPlayback(vm)

        assertEquals(1_000L, audioController.lastMultiPlaybackSpec?.startPositionMs)
        assertEquals(listOf("a", "b"), audioController.lastMultiPlaybackSpec?.lanes?.map { it.trackId })
        collectJob.cancel()
    }

    @Test
    fun `pause preserves playhead and stop while paused resets to zero`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 20_000L)),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            audioController.transportPositionMsValue = 150L
            vm.advancePlayheadNativeTransportForTests(150L)
            advanceUntilIdle()

            vm.performStopPressed()
            advanceUntilIdle()
            val pausedAt = vm.uiState.value.playheadPositionMs
            assertTrue(pausedAt > 0L)
            assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
            assertFalse(vm.uiState.value.stopButtonShowsPause)

            audioController.transportPositionMsValue = 0L
            advanceUntilIdle()
            assertEquals(pausedAt, vm.uiState.value.playheadPositionMs)

            vm.performStopPressed()
            advanceUntilIdle()
            assertEquals(0L, vm.uiState.value.playheadPositionMs)
            assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
            collectJob.cancel()
        }

    @Test
    fun `cannot deselect last selected session lane track during playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L),
                            track(id = "b", position = 1, wavFilePath = "b.wav", duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            vm.toggleSelect("b")
            startPlayback(vm)
            vm.toggleSelect("b")
            advanceUntilIdle()
            assertEquals(setOf("a"), vm.uiState.value.selectedTrackIds)

            vm.toggleSelect("a")
            advanceUntilIdle()
            assertEquals(setOf("a"), vm.uiState.value.selectedTrackIds)
            collectJob.cancel()
        }

    @Test
    fun `play after natural completion starts from zero without stale transport flash`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 5_000L)),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            audioController.transportPositionMsValue = 5_000L
            vm.advancePlayheadNativeTransportForTests(5_000L)
            advanceUntilIdle()

            audioController.completePlayback()
            advanceUntilIdle()
            assertEquals(0L, vm.uiState.value.playheadPositionMs)

            audioController.transportPositionMsValue = 5_000L
            invokePlayPressed(vm)
            advanceUntilIdle()

            assertEquals(0L, vm.uiState.value.playheadPositionMs)
            assertEquals(0L, audioController.lastMultiPlaybackSpec?.startPositionMs)
            assertEquals(5_000L, audioController.lastMultiPlaybackSpec?.sessionTimelineEndMs)
            collectJob.cancel()
        }

    @Test
    fun `natural playback completion resets playhead to zero`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 5_000L)),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            audioController.completePlayback()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
            assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
            assertEquals(0L, vm.uiState.value.playheadPositionMs)
            collectJob.cancel()
        }

    @Test
    fun `play and record backing completion clears playing markers while recording continues`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "backing", position = 0, wavFilePath = "backing.wav", duration = 30_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("backing")
            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()
            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
            assertEquals(setOf("backing"), vm.uiState.value.sessionTrackIds)

            audioController.completePlayback()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)

            val audibilityCallsBefore = audioController.armedLaneAudibilityCalls
            vm.toggleSelect("backing")
            advanceUntilIdle()
            assertEquals(audibilityCallsBefore, audioController.armedLaneAudibilityCalls)
            collectJob.cancel()
        }

    @Test
    fun `play and record with looped backing sends native loop spec and open session end`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(
                                id = "backing",
                                position = 0,
                                wavFilePath = "backing.wav",
                                duration = 30_000L,
                                isLoop = true,
                                loopStartMs = 1_000L,
                                loopEndMs = 5_000L,
                            ),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("backing")
            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()

            val spec = audioController.lastMultiPlaybackSpec!!
            assertTrue(spec.lanes.single().loopEnabled)
            assertEquals(1_000L, spec.lanes.single().loopSourceStartMs)
            assertEquals(5_000L, spec.lanes.single().loopSourceEndMs)
            assertEquals(0L, spec.sessionTimelineEndMs)
            assertEquals(setOf("backing"), vm.uiState.value.sessionTrackIds)

            audioController.completePlayback()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)

            collectJob.cancel()
        }

    @Test
    fun `native loop completion ends transport like natural completion`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(
                                id = "a",
                                position = 0,
                                wavFilePath = "a.wav",
                                isLoop = true,
                                duration = 30_000L,
                            ),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            audioController.transportPositionMsValue = 4_500L
            vm.advancePlayheadNativeTransportForTests(4_500L)
            advanceUntilIdle()
            assertEquals(4_500L, vm.uiState.value.playheadPositionMs)

            audioController.completePlayback()
            advanceUntilIdle()

            assertEquals(0L, vm.uiState.value.playheadPositionMs)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
            assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
            collectJob.cancel()
        }

    @Test
    fun `recording-only playhead follows mocked native transport`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 60_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.setPlayheadNativePollEnabledForTests(false)
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()

        audioController.transportPositionMsValue = 12_500L
        vm.advancePlayheadNativeTransportForTests(12_500L)
        advanceUntilIdle()

        assertEquals(12_500L, vm.uiState.value.playheadPositionMs)
        assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
        collectJob.cancel()
    }

    @Test
    fun `play and record handoff keeps playhead on native transport after backing ends`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "backing", position = 0, wavFilePath = "backing.wav", duration = 30_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.setPlayheadNativePollEnabledForTests(false)
            vm.toggleSelect("backing")
            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()

            audioController.transportPositionMsValue = 4_000L
            vm.advancePlayheadNativeTransportForTests(4_000L)
            advanceUntilIdle()
            assertEquals(4_000L, vm.uiState.value.playheadPositionMs)

            audioController.completePlayback()
            advanceUntilIdle()

            assertNotNull(vm.uiState.value.recordingTrackId)
            assertEquals(4_000L, vm.uiState.value.playheadPositionMs)

            audioController.transportPositionMsValue = 7_500L
            vm.advancePlayheadNativeTransportForTests(7_500L)
            advanceUntilIdle()
            assertEquals(7_500L, vm.uiState.value.playheadPositionMs)
            collectJob.cancel()
        }

    @Test
    fun `clock five completion resets playhead to zero via stopAndResetToZero`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 5_000L)),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            audioController.transportPositionMsValue = 2_000L
            vm.advancePlayheadNativeTransportForTests(2_000L)
            advanceUntilIdle()

            audioController.completePlayback()
            advanceUntilIdle()

            assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
            assertEquals(0L, vm.uiState.value.playheadPositionMs)
            collectJob.cancel()
        }

    @Test
    fun `native poll does not advance playhead when transport position is static`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 20_000L)),
                )
            val audioController = FakeAudioController()
            val vm =
                createViewModel(dao, audioController).also {
                    it.setPlayheadNativePollEnabledForTests(true)
                }
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()

            audioController.transportPositionMsValue = 1_000L
            advanceTimeBy(300)

            assertEquals(1_000L, vm.uiState.value.playheadPositionMs)

            // Stop poll before advanceUntilIdle: delay(16) loop while Playing never goes idle.
            vm.setPlayheadNativePollEnabledForTests(false)
            vm.performStopPressed()
            runCurrent()
            vm.performStopPressed()
            runCurrent()
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub during playing pauses engine and resumes on release`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs

            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()
            audioController.transportPositionMsValue = 400L
            vm.advancePlayheadNativeTransportForTests(400L)
            advanceUntilIdle()

            vm.onPlayheadScrubStarted()
            advanceUntilIdle()
            assertEquals(TransportPlaybackPhase.Playing, vm.uiState.value.transportPlaybackPhase)
            assertEquals(1, audioController.stopPlaybackCalls)
            assertEquals(setOf("a"), vm.uiState.value.sessionTrackIds)
            assertFalse(audioController.playbackState.value)

            vm.onPlayheadScrubPreviewPosition(playheadMsAtFraction(0.5f, base), base)
            advanceUntilIdle()
            assertEquals(5_000L, vm.uiState.value.playheadPositionMs)

            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0.25f, base), base)
            advanceUntilIdle()
            assertEquals(2, audioController.startPlaybackCalls)
            assertEquals(2_500L, audioController.lastMultiPlaybackSpec?.startPositionMs)
            assertEquals(TransportPlaybackPhase.Playing, vm.uiState.value.transportPlaybackPhase)
            assertTrue(audioController.playbackState.value)
            assertEquals(2_500L, vm.uiState.value.playheadPositionMs)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub with only loop track selected uses full project timeline`() =
        runTest(mainDispatcherRule.dispatcher) {
            val longMs = 300_000L
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "long", position = 0, wavFilePath = "long.wav")
                                .copy(duration = longMs),
                            track(
                                id = "loop",
                                position = 1,
                                wavFilePath = "loop.wav",
                            ).copy(
                                duration = 30_000L,
                                isLoop = true,
                                loopStartMs = 7_000L,
                                loopEndMs = 16_000L,
                            ),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val fullTimelineMs = vm.uiState.value.timelineVisibleDurationMs
            assertEquals(longMs, fullTimelineMs)

            vm.toggleSelect("loop")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()
            advanceUntilIdle()

            val seekMs = playheadMsAtFraction(0.6f, fullTimelineMs)
            vm.onPlayheadScrubStarted()
            advanceUntilIdle()
            vm.onPlayheadScrubPreviewPosition(seekMs, fullTimelineMs)
            advanceUntilIdle()
            assertEquals(seekMs, vm.uiState.value.playheadPositionMs)

            vm.onPlayheadScrubCommittedPosition(seekMs, fullTimelineMs)
            advanceUntilIdle()

            assertEquals(seekMs, audioController.lastMultiPlaybackSpec?.startPositionMs)
            assertEquals(seekMs, vm.uiState.value.playheadPositionMs)
            assertEquals(TransportPlaybackPhase.Playing, vm.uiState.value.transportPlaybackPhase)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub ignored during recording`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", duration = 10_000L)),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs
        vm.onRecordPressed(PROJECT_ID)
        advanceUntilIdle()
        val before = vm.uiState.value.playheadPositionMs
        val stopCallsBefore = audioController.stopPlaybackCalls

        vm.onPlayheadScrubStarted()
        vm.onPlayheadScrubPreviewPosition(playheadMsAtFraction(1f, base), base)
        vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(1f, base), base)
        advanceUntilIdle()

        assertEquals(before, vm.uiState.value.playheadPositionMs)
        assertEquals(stopCallsBefore, audioController.stopPlaybackCalls)
        assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
        collectJob.cancel()
    }

    @Test
    fun `transport seek scrub ignored during play and record overdub`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "backing", position = 0, wavFilePath = "backing.wav", duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("backing")
            vm.onRecordPressed(PROJECT_ID)
            advanceUntilIdle()
            val before = vm.uiState.value.playheadPositionMs
            val startCallsBefore = audioController.startPlaybackCalls

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(1f, base), base)
            advanceUntilIdle()

            assertEquals(before, vm.uiState.value.playheadPositionMs)
            assertEquals(startCallsBefore, audioController.startPlaybackCalls)
            assertEquals(TransportPlaybackPhase.Recording, vm.uiState.value.transportPlaybackPhase)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub when paused does not auto play`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                    ),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs
        vm.toggleSelect("a")
        startPlayback(vm)
        vm.performStopPressed()
        advanceUntilIdle()
        assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)

        vm.onPlayheadScrubStarted()
        vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(1f, base), base)
        advanceUntilIdle()

        assertEquals(base, vm.uiState.value.playheadPositionMs)
        assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
        assertEquals(1, audioController.startPlaybackCalls)
        collectJob.cancel()
    }

    @Test
    fun `transport seek scrub cancel leaves paused without stuck session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubPreviewPosition(playheadMsAtFraction(0.6f, vm.uiState.value.timelineBaseDurationMs), vm.uiState.value.timelineBaseDurationMs)
            vm.onPlayheadScrubCancelled()
            advanceUntilIdle()

            assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
            assertFalse(vm.uiState.value.playbackSessionActive)
            assertEquals(emptySet<String>(), vm.uiState.value.sessionTrackIds)
            assertFalse(audioController.playbackState.value)
            assertEquals(1, audioController.startPlaybackCalls)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub stop during drag leaves paused without resume`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.onPlayheadScrubStarted()
            vm.performStopPressed()
            advanceUntilIdle()

            assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
            assertFalse(vm.uiState.value.playbackSessionActive)
            assertEquals(1, audioController.startPlaybackCalls)
            assertFalse(audioController.playbackState.value)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub failed restart leaves paused not playing`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController =
                FakeAudioController(startPlaybackPermitted = { invocation -> invocation == 0 })
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0.5f, base), base)
            advanceUntilIdle()

            assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
            assertFalse(vm.uiState.value.playbackSessionActive)
            assertFalse(audioController.playbackState.value)
            assertEquals(2, audioController.startPlaybackCalls)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub commit at timeline end leaves paused`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(1f, base), base)
            advanceUntilIdle()

            assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
            assertFalse(vm.uiState.value.playbackSessionActive)
            assertEquals(base, vm.uiState.value.playheadPositionMs)
            assertEquals(1, audioController.startPlaybackCalls)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub commit at zero restarts playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()
            audioController.transportPositionMsValue = 4_000L
            vm.advancePlayheadNativeTransportForTests(4_000L)
            advanceUntilIdle()

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0f, base), base)
            advanceUntilIdle()

            assertEquals(TransportPlaybackPhase.Playing, vm.uiState.value.transportPlaybackPhase)
            assertEquals(2, audioController.startPlaybackCalls)
            assertEquals(0L, audioController.lastMultiPlaybackSpec?.startPositionMs)
            assertTrue(audioController.playbackState.value)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub restart arms all selected playable tracks`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                            track(id = "b", position = 1, wavFilePath = "b.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("a")
            vm.toggleSelect("b")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0.5f, base), base)
            advanceUntilIdle()

            assertEquals(setOf("a", "b"), vm.uiState.value.selectedTrackIds)
            assertEquals(setOf("a", "b"), vm.uiState.value.sessionTrackIds)
            assertEquals(2, audioController.lastMultiPlaybackSpec?.lanes?.size)
            assertEquals(
                setOf("a", "b"),
                audioController.lastMultiPlaybackSpec?.lanes?.map { it.trackId }?.toSet(),
            )
            assertEquals(TransportPlaybackPhase.Playing, vm.uiState.value.transportPlaybackPhase)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub after hot join restarts both selected tracks`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                            track(id = "b", position = 1, wavFilePath = "b.wav").copy(duration = 10_000L),
                        ),
                )
            val audioController =
                FakeAudioController().apply {
                    hotJoinReturnLaneIndex = 1
                    hotJoinCommitLifecycle = PlaybackLaneLifecycle.Active
                }
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()
            vm.toggleSelect("b")
            advanceUntilIdle()

            assertEquals(setOf("a", "b"), vm.uiState.value.selectedTrackIds)
            assertEquals(setOf("a"), vm.sessionTrackIdsForTests())

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0.4f, base), base)
            advanceUntilIdle()

            assertEquals(setOf("a", "b"), vm.uiState.value.sessionTrackIds)
            assertEquals(2, audioController.lastMultiPlaybackSpec?.lanes?.size)
            assertEquals(
                listOf("a", "b"),
                vm.sessionLaneTrackIdsForTests().filterNotNull(),
            )
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub restart includes only playable selected tracks`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                            track(id = "b", position = 1, wavFilePath = ""),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("a")
            vm.toggleSelect("b")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0.3f, base), base)
            advanceUntilIdle()

            assertEquals(setOf("a", "b"), vm.uiState.value.selectedTrackIds)
            assertEquals(setOf("a"), vm.uiState.value.sessionTrackIds)
            assertEquals(1, audioController.lastMultiPlaybackSpec?.lanes?.size)
            assertEquals("a", audioController.lastMultiPlaybackSpec?.lanes?.single()?.trackId)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub with no playable selection leaves paused`() =
        runTest(mainDispatcherRule.dispatcher) {
            val dao =
                FakeProjectDao(
                    projects = listOf(project()),
                    tracks =
                        listOf(
                            track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                            track(id = "b", position = 1, wavFilePath = ""),
                        ),
                )
            val audioController = FakeAudioController()
            val vm = createViewModel(dao, audioController)
            val collectJob = backgroundScope.launch { vm.uiState.collect { } }

            vm.bind(PROJECT_ID)
            advanceUntilIdle()
            val base = vm.uiState.value.timelineBaseDurationMs
            vm.toggleSelect("a")
            startPlayback(vm)
            vm.cancelPlaybackCompletionMonitorForTests()
            vm.setSelectedTrackIdsForTests(setOf("b"))

            vm.onPlayheadScrubStarted()
            vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0.5f, base), base)
            advanceUntilIdle()

            assertEquals(TransportPlaybackPhase.Paused, vm.uiState.value.transportPlaybackPhase)
            assertFalse(vm.uiState.value.playbackSessionActive)
            assertFalse(audioController.playbackState.value)
            collectJob.cancel()
        }

    @Test
    fun `transport seek scrub when idle does not auto play`() = runTest(mainDispatcherRule.dispatcher) {
        val dao =
            FakeProjectDao(
                projects = listOf(project()),
                tracks =
                    listOf(
                        track(id = "a", position = 0, wavFilePath = "a.wav").copy(duration = 10_000L),
                    ),
            )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        val base = vm.uiState.value.timelineBaseDurationMs

        vm.onPlayheadScrubCommittedPosition(playheadMsAtFraction(0.5f, base), base)
        advanceUntilIdle()

        assertEquals(5_000L, vm.uiState.value.playheadPositionMs)
        assertEquals(TransportPlaybackPhase.Idle, vm.uiState.value.transportPlaybackPhase)
        assertEquals(0, audioController.startPlaybackCalls)
        collectJob.cancel()
    }

    private fun project(name: String = "Project", id: String = PROJECT_ID) = ProjectEntity(id = id, name = name)

    private fun track(
        id: String,
        position: Int,
        name: String = "Track $id",
        wavFilePath: String = "",
        gain: Float = 100f,
        isLoop: Boolean = false,
        loopStartMs: Long = 0L,
        loopEndMs: Long? = null,
        duration: Long? = null,
        timelineStartOffsetMs: Long = 0L,
    ) = TrackEntity(
        id = id,
        projectId = PROJECT_ID,
        name = name,
        position = position,
        wavFilePath = wavFilePath,
        gain = gain,
        isLoop = isLoop,
        loopStartMs = loopStartMs,
        loopEndMs = loopEndMs,
        duration = duration,
        timelineStartOffsetMs = timelineStartOffsetMs,
    )

    private companion object {
        const val PROJECT_ID = "project-1"
        const val PROJECT_2_ID = "project-2"
    }
}

private fun playheadMsAtFraction(fraction: Float, timelineDurationMs: Long): Long =
    timelinePlayheadPositionMs(fraction, timelineDurationMs)

internal object NoopProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit
    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

private object NullTrackOutputPathProvider : AudioFilePathProvider {
    override fun projectRecordingDirectory(projectId: String): String? = null

    override fun trackOutputPath(projectId: String, trackId: String): String? = null

    override fun trackRecordingTempPath(projectId: String, trackId: String): String? = null
}

private class FakeAudioImporter(
    private val result: AudioImportResult = AudioImportResult.Success(
        durationMs = 1_000L,
        channelMode = ChannelMode.MONO,
        channelCount = 1,
    ),
    private val wavSamplesToWrite: ShortArray? = null
) : AudioImporter {
    var importCalls: Int = 0
        private set
    var lastTarget: AudioImportTarget? = null
        private set
    var lastDestination: String? = null
        private set

    override suspend fun import(
        source: AudioImportSource,
        destinationPath: String,
        target: AudioImportTarget
    ): AudioImportResult {
        importCalls += 1
        lastTarget = target
        lastDestination = destinationPath
        wavSamplesToWrite?.let { samples ->
            val destination = File(destinationPath)
            destination.parentFile?.mkdirs()
            tempWav(samples).copyTo(destination, overwrite = true)
        }
        return result
    }
}

private class FakeAudioFilePathProvider(
    private val basePath: String = "imports"
) : AudioFilePathProvider {
    override fun projectRecordingDirectory(projectId: String): String = "$basePath/$projectId"

    override fun trackOutputPath(projectId: String, trackId: String): String =
        "$basePath/$projectId/$trackId.wav"

    override fun trackRecordingTempPath(projectId: String, trackId: String): String =
        "$basePath/$projectId/$trackId.recording.tmp.wav"
}

internal class FakeAudioController(
    private val startRecordingPath: String? = "recordings/project-1/default.wav",
    private val stopRecordingResult: Boolean = true,
    private val startPlaybackResult: Boolean = true,
    private val stopPlaybackResult: Boolean = true,
    /**
     * Per-invocation gate for [startPlayback]. Index is 0-based across the test lifetime.
     * Return value is AND-ed with [startPlaybackResult] for both the return and [playbackState].
     */
    private val startPlaybackPermitted: (Int) -> Boolean = { _ -> true },
) : AudioController {
    var startPlaybackCalls = 0
        private set
    var lastPlaybackGain: Float? = null
        private set
    var lastMultiPlaybackSpec: MultiPlaybackSpec? = null
        private set
    var lastPlaybackSpec: PlaybackSpec? = null
        private set
    var lastRecordingSpec: RecordingSpec? = null
        private set

    /** Test hook invoked at the beginning of native [startRecording] (before JNI work). */
    var onEnterStartRecording: (() -> Unit)? = null

    var stopRecordingCalls = 0
        private set
    var stopPlaybackCalls = 0
        private set
    private var startPlaybackInvocationIndex = 0
    private val _playbackState = MutableStateFlow(false)
    override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    private val _recordingInputLevel = MutableStateFlow(0f)
    override val recordingInputLevel: StateFlow<Float> = _recordingInputLevel.asStateFlow()

    var transportPositionMsValue: Long = 0L

    override fun transportPositionMs(): Long = transportPositionMsValue

    override fun isPlaybackEngineRunning(): Boolean = _playbackState.value

    /** Simulates the engine reporting playback completion. */
    fun completePlayback() {
        _playbackState.value = false
    }

    fun emitRecordingInputLevel(level: Float) {
        _recordingInputLevel.value = level
    }

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? {
        onEnterStartRecording?.invoke()
        lastRecordingSpec = spec
        transportPositionMsValue = spec.timelineStartOffsetMs
        if (startRecordingPath == null) return null
        return outputPath ?: startRecordingPath?.replace("default", spec.trackId)
    }

    override fun stopRecording(): Boolean {
        stopRecordingCalls += 1
        _recordingInputLevel.value = 0f
        return stopRecordingResult
    }

    override fun startPlayback(spec: PlaybackSpec): Boolean {
        startPlaybackCalls += 1
        lastPlaybackSpec = spec
        lastPlaybackGain = spec.gain
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
        startPlaybackCalls += 1
        lastMultiPlaybackSpec = spec
        transportPositionMsValue = spec.startPositionMs
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun setPlaybackGain(gain: Float) {
        lastPlaybackGain = gain
    }

    var lastArmedLaneAudibility: BooleanArray? = null
        private set
    var armedLaneAudibilityCalls = 0

    override fun setArmedPlaybackLaneAudibility(audibleByLaneIndex: BooleanArray) {
        armedLaneAudibilityCalls += 1
        lastArmedLaneAudibility = audibleByLaneIndex.copyOf()
    }

    val playbackLaneAudibleCalls = mutableListOf<Pair<Int, Boolean>>()

    override fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean) {
        playbackLaneAudibleCalls.add(laneIndex to audible)
    }

    var beginHotJoinCalls = 0
        private set
    var lastHotJoinWavPath: String? = null
        private set
    var lastHotJoinGain: Float? = null
        private set
    var lastHotJoinClipStartMs: Long? = null
        private set
    var lastHotJoinClipDurationMs: Long? = null
        private set
    var hotJoinReturnLaneIndex: Int = 1
    var hotJoinCommitLifecycle: PlaybackLaneLifecycle = PlaybackLaneLifecycle.Active
    private val laneLifecycleOverrides = mutableMapOf<Int, PlaybackLaneLifecycle>()

    override fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
    ): Int {
        beginHotJoinCalls += 1
        lastHotJoinWavPath = wavFilePath
        lastHotJoinGain = gain
        lastHotJoinClipStartMs = timelineClipStartMs
        lastHotJoinClipDurationMs = timelineClipDurationMs
        laneLifecycleOverrides[hotJoinReturnLaneIndex] = PlaybackLaneLifecycle.Preparing
        laneLifecycleOverrides[hotJoinReturnLaneIndex] = hotJoinCommitLifecycle
        return hotJoinReturnLaneIndex
    }

    override fun cancelHotJoinLane(laneIndex: Int) {
        laneLifecycleOverrides[laneIndex] = PlaybackLaneLifecycle.Cancelled
    }

    override fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle =
        laneLifecycleOverrides[laneIndex] ?: PlaybackLaneLifecycle.Inactive

    override fun stopPlayback(): Boolean {
        stopPlaybackCalls += 1
        _playbackState.value = false
        transportPositionMsValue = 0L
        return stopPlaybackResult
    }

    var releaseCalls = 0
        private set

    override fun release() {
        releaseCalls += 1
        _playbackState.value = false
        _recordingInputLevel.value = 0f
    }
}

internal class FakeProjectDao(
    projects: List<ProjectEntity> = emptyList(),
    tracks: List<TrackEntity> = emptyList(),
    private val failUpsertProject: Boolean = false,
    private val failUpsertTrack: Boolean = false,
    private val failDeleteTrackAndUpdatePositions: Boolean = false,
    private val updateTracksGate: CompletableDeferred<Unit>? = null
) : ProjectDao {
    private val projectsFlow = MutableStateFlow(projects.sortedByDescending { it.createdAt })
    private val tracksByProject = tracks.groupBy { it.projectId }
        .mapValues { (_, list) -> MutableStateFlow(list.sortedBy { it.position }) }
        .toMutableMap()

    override suspend fun upsertProject(project: ProjectEntity) {
        if (failUpsertProject) error("upsertProject failed")
        projectsFlow.value = (projectsFlow.value.filterNot { it.id == project.id } + project)
            .sortedByDescending { it.createdAt }
    }

    override fun observeProjects(): Flow<List<ProjectEntity>> = projectsFlow

    override fun observeProject(projectId: String): Flow<ProjectEntity?> =
        projectsFlow.map { projects -> projects.firstOrNull { it.id == projectId } }

    override suspend fun projectExists(projectId: String): Boolean =
        projectsFlow.value.any { it.id == projectId }

    override suspend fun deleteProject(projectId: String) {
        projectsFlow.value = projectsFlow.value.filterNot { it.id == projectId }
        tracksByProject.remove(projectId)
    }

    override fun observeTracks(projectId: String): Flow<List<TrackEntity>> =
        tracksByProject.getOrPut(projectId) { MutableStateFlow(emptyList()) }

    override suspend fun upsertTrack(track: TrackEntity) {
        if (failUpsertTrack) error("upsertTrack failed")
        val flow = tracksByProject.getOrPut(track.projectId) { MutableStateFlow(emptyList()) }
        flow.value = (flow.value.filterNot { it.id == track.id } + track).sortedBy { it.position }
    }

    override suspend fun upsertTracks(tracks: List<TrackEntity>) {
        tracks.forEach { upsertTrack(it) }
    }

    override suspend fun updateTracks(tracks: List<TrackEntity>) {
        if (tracks.isEmpty()) return
        updateTracksGate?.await()
        tracksByProject.getOrPut(tracks.first().projectId) { MutableStateFlow(emptyList()) }
            .value = tracks.sortedBy { it.position }
    }

    override suspend fun deleteTrack(trackId: String) {
        tracksByProject.values.forEach { flow ->
            flow.value = flow.value.filterNot { it.id == trackId }
        }
    }

    override suspend fun deleteTrackAndUpdatePositions(trackId: String, remaining: List<TrackEntity>) {
        if (failDeleteTrackAndUpdatePositions) error("deleteTrackAndUpdatePositions failed")
        super.deleteTrackAndUpdatePositions(trackId, remaining)
    }
}
