package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.PlaybackSpec
import com.georgv.audioworkstation.core.audio.RecordingSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProjectTransportControllerTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    private companion object {
        const val PID = "project-transport-test"
    }

    private fun project() = ProjectEntity(id = PID, name = "P")

    private fun track(id: String) =
        TrackEntity(
            id = id,
            projectId = PID,
            name = id,
            position = 0,
            wavFilePath = "$id.wav",
            channelMode = ChannelMode.MONO,
        )

    @Test
    fun `stopAll clears recording markers and stops recorder before playback engine when both active`() =
        runTest(mainDispatcherRule.dispatcher) {
            val journal = mutableListOf<String>()
            val audio = JournalAudioController(journal)

            var recId: String? = "a"
            var optimistic: TrackEntity? = track("a")
            var startupFlag = true
            val finalizedIds = mutableListOf<String>()

            val playback = PlaybackSessionController(
                scope = this,
                audioController = audio,
                loadCurrentProject = { if (it == PID) project() else null },
                currentProjectId = { PID },
                visibleTracks = { emptyList() },
            )

            assertTrue(audio.startPlayback(PlaybackSpec(48_000, "x.wav", 1f)))
            playback.markPlayingAndStartCompletionMonitor("x")
            advanceUntilIdle()

            val sut = ProjectTransportController(
                audioController = audio,
                playbackSession = playback,
                getRecordingTrackId = { recId },
                setRecordingTrackId = { recId = it },
                setOptimisticRecordingTrack = { optimistic = it },
                setRecordingStartup = { startupFlag = it },
                finalizeRecordingTrackAfterSuccessfulEngineStop = { finalizedIds += it },
            )

            sut.stopAll()

            assertEquals(false, startupFlag)
            assertNull(recId)
            assertNull(optimistic)
            assertEquals(emptySet<String>(), playback.playingTrackIds.value)
            assertEquals(listOf("a"), finalizedIds)
            assertEquals(listOf("stopRecording", "stopPlayback"), audio.engineStopJournal)
        }

    @Test
    fun `stopAll skips recorder when no recording row and still stops playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val journal = mutableListOf<String>()
            val audio = JournalAudioController(journal)

            var recId: String? = null
            val playback = PlaybackSessionController(
                scope = this,
                audioController = audio,
                loadCurrentProject = { if (it == PID) project() else null },
                currentProjectId = { PID },
                visibleTracks = { emptyList() },
            )
            assertTrue(audio.startPlayback(PlaybackSpec(48_000, "x.wav", 1f)))
            playback.markPlayingAndStartCompletionMonitor("x")
            advanceUntilIdle()

            val sut = ProjectTransportController(
                audioController = audio,
                playbackSession = playback,
                getRecordingTrackId = { recId },
                setRecordingTrackId = { recId = it },
                setOptimisticRecordingTrack = { },
                setRecordingStartup = { },
                finalizeRecordingTrackAfterSuccessfulEngineStop = { error("finalize not expected") },
            )

            sut.stopAll()

            assertEquals(listOf("stopPlayback"), audio.engineStopJournal)
        }

    @Test
    fun `resetPlaybackForProjectChange clears playing markers via session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = JournalAudioController(mutableListOf())
            val playback = PlaybackSessionController(
                scope = this,
                audioController = audio,
                loadCurrentProject = { project() },
                currentProjectId = { PID },
                visibleTracks = { emptyList() },
            )
            assertTrue(audio.startPlayback(PlaybackSpec(48_000, "x.wav", 1f)))
            playback.markPlayingAndStartCompletionMonitor("z")
            advanceUntilIdle()

            val sut =
                ProjectTransportController(
                    audioController = audio,
                    playbackSession = playback,
                    getRecordingTrackId = { null },
                    setRecordingTrackId = { },
                    setOptimisticRecordingTrack = { },
                    setRecordingStartup = { },
                    finalizeRecordingTrackAfterSuccessfulEngineStop = { },
                )
            sut.resetPlaybackForProjectChange()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), playback.playingTrackIds.value)
        }

    /** Records [stopRecording]/[stopPlayback] relative order only; stubs other [AudioController] surface used in tests. */
    private class JournalAudioController(private val journal: MutableList<String>) : AudioController {

        private var startPlaybackCalls = 0
        private val _playbackState = MutableStateFlow(false)
        override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()

        val engineStopJournal: List<String>
            get() =
                journal.filter { it.startsWith("stopRecording") || it.startsWith("stopPlayback") }.map {
                    when {
                        it.startsWith("stopRecording") -> "stopRecording"
                        else -> "stopPlayback"
                    }
                }

        override fun startRecording(spec: RecordingSpec): String? = null

        override fun stopRecording(): Boolean {
            journal += "stopRecording"
            return true
        }

        override fun startPlayback(spec: PlaybackSpec): Boolean {
            startPlaybackCalls++
            journal += "startPlayback:$startPlaybackCalls"
            _playbackState.value = true
            return true
        }

        override fun setPlaybackGain(gain: Float) = Unit

        override fun stopPlayback(): Boolean {
            journal += "stopPlayback"
            _playbackState.value = false
            return true
        }

        override fun release() {
            _playbackState.value = false
        }
    }
}
