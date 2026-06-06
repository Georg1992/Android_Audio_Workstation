package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayAndRecordTransportTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    @Test
    fun `startFromPlayhead skips playback when no overdub lanes`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = FakeAudioController()
        val playback = playbackSession(this, audio)
        val sut = PlayAndRecordTransport(audio, playback, TestAppDispatchers.unified(mainDispatcherRule.dispatcher))

        assertTrue(
            sut.startFromPlayhead(
                project = ProjectEntity(id = "p", name = "P"),
                selectedPlayableTracks = emptyList(),
                recordingTrackId = "rec",
                startPositionMs = 0L,
                sessionTimelineEndMs = 10_000L,
            ),
        )
        assertEquals(0, audio.startPlaybackCalls)
    }

    @Test
    fun `startFromPlayhead excludes recording track from playback lanes`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = FakeAudioController()
        val playback = playbackSession(this, audio)
        val sut = PlayAndRecordTransport(audio, playback, TestAppDispatchers.unified(mainDispatcherRule.dispatcher))
        val backing =
            TrackEntity(id = "backing", projectId = "p", wavFilePath = "/backing.wav")
        val recordingRow =
            TrackEntity(id = "rec", projectId = "p", wavFilePath = "/rec.wav")

        assertTrue(
            sut.startFromPlayhead(
                project = ProjectEntity(id = "p", name = "P"),
                selectedPlayableTracks = listOf(backing, recordingRow),
                recordingTrackId = "rec",
                startPositionMs = 5_000L,
                sessionTimelineEndMs = 30_000L,
            ),
        )

        assertEquals(1, audio.startPlaybackCalls)
        assertEquals(5_000L, audio.lastMultiPlaybackSpec?.startPositionMs)
        assertEquals(listOf("backing"), audio.lastMultiPlaybackSpec?.lanes?.map { it.trackId })
        assertEquals(setOf("backing"), playback.sessionTrackIds.value)
        sut.stop()
        advanceUntilIdle()
    }

    @Test
    fun `stop clears playback session markers`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = FakeAudioController()
        val playback = playbackSession(this, audio)
        val sut = PlayAndRecordTransport(audio, playback, TestAppDispatchers.unified(mainDispatcherRule.dispatcher))
        val track = TrackEntity(id = "a", projectId = "p", wavFilePath = "/a.wav")

        sut.startFromPlayhead(
            project = ProjectEntity(id = "p", name = "P"),
            selectedPlayableTracks = listOf(track),
            recordingTrackId = "rec",
            startPositionMs = 0L,
            sessionTimelineEndMs = 10_000L,
        )
        advanceUntilIdle()
        sut.stop()
        advanceUntilIdle()

        assertEquals(1, audio.stopPlaybackCalls)
        assertEquals(emptySet<String>(), playback.sessionTrackIds.value)
    }

    private fun playbackSession(scope: TestScope, audio: FakeAudioController): PlaybackSessionController =
        PlaybackSessionController(
            scope = scope,
            audioController = audio,
            dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
            loadCurrentProject = { _ -> ProjectEntity(id = "p", name = "P") },
            currentProjectId = { "p" },
            visibleTracks = { emptyList() },
        )
}
