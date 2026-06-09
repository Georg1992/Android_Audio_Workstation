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
    fun `rebuild skips playback when no overdub lanes`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = FakeAudioController()
        val playback = playbackSession(this, audio)
        val sut = PlayAndRecordTransport(audio, playback, TestAppDispatchers.unified(mainDispatcherRule.dispatcher))

        assertTrue(
            sut.rebuildOverdubAtCurrentTransport(
                project = ProjectEntity(id = "p", name = "P"),
                selectedPlayableTracks = emptyList(),
                recordingTrackId = "rec",
                transportMs = 0L,
                sessionTimelineEndMs = 10_000L,
                timelineVisibleDurationMs = 10_000L,
            ),
        )
        assertEquals(0, audio.rearmOverdubPlaybackCalls)
    }

    @Test
    fun `rebuild excludes recording track from playback lanes`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = FakeAudioController()
        val playback = playbackSession(this, audio)
        val sut = PlayAndRecordTransport(audio, playback, TestAppDispatchers.unified(mainDispatcherRule.dispatcher))
        val backing =
            TrackEntity(id = "backing", projectId = "p", wavFilePath = "/backing.wav")
        val recordingRow =
            TrackEntity(id = "rec", projectId = "p", wavFilePath = "/rec.wav")

        assertTrue(
            sut.rebuildOverdubAtCurrentTransport(
                project = ProjectEntity(id = "p", name = "P"),
                selectedPlayableTracks = listOf(backing, recordingRow),
                recordingTrackId = "rec",
                transportMs = 5_000L,
                sessionTimelineEndMs = 30_000L,
                timelineVisibleDurationMs = 30_000L,
            ),
        )

        assertEquals(1, audio.rearmOverdubPlaybackCalls)
        assertEquals(5_000L, audio.lastRearmOverdubPlaybackSpec?.startPositionMs)
        assertEquals(listOf("backing"), audio.lastRearmOverdubPlaybackSpec?.lanes?.map { it.trackId })
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

        sut.rebuildOverdubAtCurrentTransport(
            project = ProjectEntity(id = "p", name = "P"),
            selectedPlayableTracks = listOf(track),
            recordingTrackId = "rec",
            transportMs = 0L,
            sessionTimelineEndMs = 10_000L,
            timelineVisibleDurationMs = 10_000L,
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
