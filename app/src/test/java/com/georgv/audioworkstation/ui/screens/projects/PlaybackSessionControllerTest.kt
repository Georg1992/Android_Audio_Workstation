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
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackSessionControllerTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    private val projectFix = ProjectEntity(id = PROJECT_ID, name = "Project")

    private fun track(id: String, loop: Boolean, wav: String = "$id.wav") =
        TrackEntity(
            id = id,
            projectId = PROJECT_ID,
            name = id,
            position = 0,
            wavFilePath = wav,
            isLoop = loop,
            channelMode = ChannelMode.MONO,
        )

    private fun engineSpec() = PlaybackSpec(sampleRate = 48_000, wavFilePath = "a.wav", gain = 1f)

    /** Matches [ProjectViewModel.onPlayPressed]: native start then [PlaybackSessionController.markPlayingAndStartCompletionMonitor]. */
    private suspend fun TestScope.armPlaybackMonitor(audio: PlaybackSessionTestAudio, sut: PlaybackSessionController) {
        assertTrue(audio.startPlayback(engineSpec()))
        sut.markPlayingAndStartCompletionMonitor("a")
        advanceUntilIdle()
    }

    @Test
    fun `playback completion clears playing ids when loop is off`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = PlaybackSessionTestAudio()
        val visible = listOf(track("a", loop = false))
        val sut = PlaybackSessionController(
            scope = this,
            audioController = audio,
            loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
            currentProjectId = { PROJECT_ID },
            visibleTracks = { visible },
        )
        armPlaybackMonitor(audio, sut)
        assertEquals(setOf("a"), sut.playingTrackIds.value)
        audio.finishPlaybackPulse()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), sut.playingTrackIds.value)
    }

    @Test
    fun `playback completion restarts native playback when loop is on`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = PlaybackSessionTestAudio()
        val visible = listOf(track("a", loop = true))
        val sut = PlaybackSessionController(
            scope = this,
            audioController = audio,
            loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
            currentProjectId = { PROJECT_ID },
            visibleTracks = { visible },
        )
        armPlaybackMonitor(audio, sut)
        assertEquals(setOf("a"), sut.playingTrackIds.value)
        audio.finishPlaybackPulse()
        advanceUntilIdle()
        assertEquals(2, audio.startPlaybackCalls)
        assertEquals(setOf("a"), sut.playingTrackIds.value)
        sut.cancelCompletionMonitorForTransportStop()
    }

    @Test
    fun `playback completion clears playing when loop restart is rejected by engine`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(startPlaybackPermitted = { invocation -> invocation != 1 })
            val visible = listOf(track("a", loop = true))
            val sut = PlaybackSessionController(
                scope = this,
                audioController = audio,
                loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                currentProjectId = { PROJECT_ID },
                visibleTracks = { visible },
            )
            armPlaybackMonitor(audio, sut)
            audio.finishPlaybackPulse()
            advanceUntilIdle()
            assertEquals(2, audio.startPlaybackCalls)
            assertEquals(emptySet<String>(), sut.playingTrackIds.value)
        }

    @Test
    fun `transport teardown methods clear playing ids and mute completion handling`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible = listOf(track("a", loop = true))
            val sut = PlaybackSessionController(
                scope = this,
                audioController = audio,
                loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                currentProjectId = { PROJECT_ID },
                visibleTracks = { visible },
            )
            armPlaybackMonitor(audio, sut)
            sut.cancelCompletionMonitorForTransportStop()
            sut.stopEngineIfMarkedPlaying()
            sut.clearPlayingTransportState()

            audio.finishPlaybackPulse()
            advanceUntilIdle()

            assertEquals(1, audio.startPlaybackCalls)
            assertEquals(1, audio.stopPlaybackCalls)
            assertEquals(emptySet<String>(), sut.playingTrackIds.value)
        }

    private companion object {
        const val PROJECT_ID = "playback-session-project"
    }
}

/** Minimal fake for [PlaybackSessionController] tests; mirrors [FakeAudioController] semantics. */
private class PlaybackSessionTestAudio(
    private val startPlaybackResult: Boolean = true,
    private val stopPlaybackResult: Boolean = true,
    private val startPlaybackPermitted: (Int) -> Boolean = { _ -> true },
) : AudioController {
    var startPlaybackCalls = 0
        private set

    var stopPlaybackCalls = 0
        private set

    private var startPlaybackInvocationIndex = 0

    private val _playbackState = MutableStateFlow(false)
    override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()

    fun finishPlaybackPulse() {
        _playbackState.value = false
    }

    override fun startRecording(spec: RecordingSpec): String? = null

    override fun stopRecording(): Boolean = true

    override fun startPlayback(spec: PlaybackSpec): Boolean {
        startPlaybackCalls += 1
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun setPlaybackGain(gain: Float) = Unit

    override fun stopPlayback(): Boolean {
        stopPlaybackCalls += 1
        _playbackState.value = false
        return stopPlaybackResult
    }

    override fun release() {
        _playbackState.value = false
    }
}
