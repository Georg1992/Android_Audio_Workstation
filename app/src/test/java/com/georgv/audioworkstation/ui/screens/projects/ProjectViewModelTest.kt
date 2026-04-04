package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.PlaybackSpec
import com.georgv.audioworkstation.core.audio.RecordingSpec
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `bind loads tracks ordered by position`() = runTest {
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
    fun `toggleSelect adds and removes selected track`() = runTest {
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
    fun `onPlayPressed starts playback and requires explicit stop before restart`() = runTest {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        vm.onPlayPressed()
        runCurrent()
        assertEquals(setOf("a"), vm.uiState.value.playingTrackIds)

        vm.onPlayPressed()
        runCurrent()
        assertEquals(setOf("a"), vm.uiState.value.playingTrackIds)
        assertEquals(0, audioController.stopPlaybackCalls)
        assertEquals("Stop playback before starting playback again.", vm.userMessages.first())
        vm.onStopPressed()
        runCurrent()
        collectJob.cancel()
    }

    @Test
    fun `onPlayPressed rejects multi-track playback requests`() = runTest {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(
                track(id = "a", position = 0, wavFilePath = "a.wav"),
                track(id = "b", position = 1, wavFilePath = "b.wav")
            )
        )
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        vm.toggleSelect("b")
        advanceUntilIdle()

        vm.onPlayPressed()
        runCurrent()

        assertEquals(emptySet<String>(), vm.uiState.value.playingTrackIds)
        assertEquals(0, audioController.startPlaybackCalls)
        assertEquals("Playback currently supports one selected track at a time.", vm.userMessages.first())
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed requires explicit stop before starting a new take`() = runTest {
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
        assertEquals("Stop recording before starting a new take.", vm.userMessages.first())
        collectJob.cancel()
    }

    @Test
    fun `onStopPressed clears recording and playing state`() = runTest {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")))
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        runCurrent()
        vm.toggleSelect("a")
        vm.onPlayPressed()
        runCurrent()
        vm.onRecordPressed(PROJECT_ID)
        runCurrent()

        vm.onStopPressed()
        runCurrent()

        assertEquals(emptySet<String>(), vm.uiState.value.playingTrackIds)
        assertNull(vm.uiState.value.recordingTrackId)
        collectJob.cancel()
    }

    @Test
    fun `renameTrack updates session and persists to dao`() = runTest {
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
    fun `renameTrack rejects blank names and emits feedback`() = runTest {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, name = "Old")))
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.renameTrack("a", "   ")
        advanceUntilIdle()
        val message = vm.userMessages.first()

        assertEquals("Old", vm.uiState.value.tracks.single().name)
        assertEquals("Track name cannot be blank.", message)
        collectJob.cancel()
    }

    @Test
    fun `renameTrack rolls back when persistence fails`() = runTest {
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
        assertEquals("Failed to rename track.", message)
        collectJob.cancel()
    }

    @Test
    fun `setTrackGain persists gain to dao`() = runTest {
        val dao = FakeProjectDao(
            projects = listOf(project()),
            tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav", gain = 100f))
        )
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.setTrackGain("a", 42f)
        advanceUntilIdle()

        assertEquals(42f, vm.uiState.value.tracks.single().gain)
        assertEquals(42f, dao.observeTracks(PROJECT_ID).first().single().gain)
        collectJob.cancel()
    }

    @Test
    fun `setTrackGain pushes live gain to active playback`() = runTest {
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
        vm.onPlayPressed()
        runCurrent()

        vm.setTrackGain("a", 25f)
        runCurrent()

        assertEquals(0.25f, audioController.lastPlaybackGain)
        vm.onStopPressed()
        runCurrent()
        collectJob.cancel()
    }

    @Test
    fun `setTrackOrderSession and persistTrackOrderToDb keep reordered positions`() = runTest {
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
    fun `persistTrackOrderToDb keeps local reordered UI while db write is pending`() = runTest {
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
    fun `deleteTrack blocks deleting active recording track`() = runTest {
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
        assertEquals("Stop recording before deleting this track.", vm.userMessages.first())
        collectJob.cancel()
    }

    @Test
    fun `deleteTrack blocks deleting active playback track`() = runTest {
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
        vm.onPlayPressed()
        runCurrent()

        vm.deleteTrack("a")
        runCurrent()

        assertEquals(listOf("a", "b"), vm.uiState.value.tracks.map { it.id })
        assertEquals(setOf("a"), vm.uiState.value.playingTrackIds)
        assertEquals("Stop playback before deleting this track.", vm.userMessages.first())
        vm.onStopPressed()
        runCurrent()
        collectJob.cancel()
    }

    @Test
    fun `deleteTrack removes inactive track renumbers positions and clears selection`() = runTest {
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
    fun `deleteTrack rolls back when persistence fails`() = runTest {
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
        assertEquals("Failed to delete track.", message)
        collectJob.cancel()
    }

    @Test
    fun `addTrack creates missing project before adding track`() = runTest {
        val dao = FakeProjectDao()
        val vm = createViewModel(dao)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()

        vm.addTrack(PROJECT_ID)
        advanceUntilIdle()

        assertNotNull(vm.uiState.value.project)
        assertEquals(PROJECT_ID, vm.uiState.value.project?.id)
        assertEquals(listOf("Take 1"), vm.uiState.value.tracks.map { it.name })
        assertEquals(PROJECT_ID, dao.observeProject(PROJECT_ID).first()?.id)
        collectJob.cancel()
    }

    @Test
    fun `onRecordPressed creates missing project with provided name and default track settings`() = runTest {
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
        assertEquals(48_000, project?.sampleRate)
        assertEquals(16, project?.fileBitDepth)
        assertEquals(120f, project?.tempo)
        assertEquals(4, project?.timeSignatureNumerator)
        assertEquals(4, project?.timeSignatureDenominator)
        assertEquals(track.id, vm.uiState.value.recordingTrackId)
        assertEquals(ChannelMode.MONO, track.channelMode)
        assertEquals(0, track.inputChannel)
        assertEquals("Take 1", track.name)
        assertEquals("recordings/$PROJECT_ID/${track.id}.wav", track.wavFilePath)
        collectJob.cancel()
    }

    @Test
    fun `onCleared stops active transport without releasing engine`() = runTest {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        vm.onPlayPressed()
        vm.onRecordPressed(PROJECT_ID)
        runCurrent()

        val onCleared = vm.javaClass.getDeclaredMethod("onCleared")
        onCleared.isAccessible = true
        onCleared.invoke(vm)

        assertEquals(1, audioController.stopRecordingCalls)
        assertEquals(1, audioController.stopPlaybackCalls)
        assertEquals(0, audioController.releaseCalls)
        collectJob.cancel()
    }

    @Test
    fun `playback monitor clears playing state when native playback completes`() = runTest {
        val dao = FakeProjectDao(projects = listOf(project()), tracks = listOf(track(id = "a", position = 0, wavFilePath = "a.wav")))
        val audioController = FakeAudioController()
        val vm = createViewModel(dao, audioController)
        val collectJob = backgroundScope.launch { vm.uiState.collect { } }

        vm.bind(PROJECT_ID)
        advanceUntilIdle()
        vm.toggleSelect("a")
        advanceUntilIdle()

        vm.onPlayPressed()
        runCurrent()
        assertEquals(setOf("a"), vm.uiState.value.playingTrackIds)

        audioController.playbackActive = false
        advanceTimeBy(50)
        runCurrent()

        assertEquals(emptySet<String>(), vm.uiState.value.playingTrackIds)
        collectJob.cancel()
    }

    private fun createViewModel(
        dao: FakeProjectDao,
        audioController: AudioController = FakeAudioController()
    ): ProjectViewModel {
        return ProjectViewModel(ProjectRepository(dao), audioController)
    }

    private fun project() = ProjectEntity(id = PROJECT_ID, name = "Project")

    private fun track(
        id: String,
        position: Int,
        name: String = "Track $id",
        wavFilePath: String = "",
        gain: Float = 100f
    ) = TrackEntity(
        id = id,
        projectId = PROJECT_ID,
        name = name,
        position = position,
        wavFilePath = wavFilePath,
        gain = gain
    )

    private companion object {
        const val PROJECT_ID = "project-1"
    }
}

private class FakeAudioController(
    private val startRecordingPath: String? = "recordings/project-1/default.wav",
    private val stopRecordingResult: Boolean = true,
    private val startPlaybackResult: Boolean = true,
    private val stopPlaybackResult: Boolean = true
) : AudioController {
    var startPlaybackCalls = 0
        private set
    var lastPlaybackGain: Float? = null
        private set
    var stopRecordingCalls = 0
        private set
    var stopPlaybackCalls = 0
        private set
    var releaseCalls = 0
        private set
    var playbackActive = false

    override fun startRecording(spec: RecordingSpec): String? =
        startRecordingPath?.replace("default", spec.trackId)

    override fun stopRecording(): Boolean {
        stopRecordingCalls += 1
        return stopRecordingResult
    }

    override fun startPlayback(spec: PlaybackSpec): Boolean {
        startPlaybackCalls += 1
        lastPlaybackGain = spec.gain
        playbackActive = startPlaybackResult
        return startPlaybackResult
    }

    override fun setPlaybackGain(gain: Float) {
        lastPlaybackGain = gain
    }

    override fun isPlaybackActive(): Boolean = playbackActive

    override fun stopPlayback(): Boolean {
        stopPlaybackCalls += 1
        playbackActive = false
        return stopPlaybackResult
    }

    override fun release() {
        releaseCalls += 1
    }
}

private class FakeProjectDao(
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
