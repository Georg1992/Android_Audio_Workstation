package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.MasterOutputMeterState
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackLaneLifecycle
import com.georgv.audioworkstation.core.audio.TrackPlaybackLane
import com.georgv.audioworkstation.core.audio.toMultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.RecordingSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    private fun engineSpec() =
        MultiPlaybackSpec(
            sampleRate = 48_000,
            lanes = listOf(TrackPlaybackLane("a", "a.wav", 1f)),
        )

    /** Matches [ProjectViewModel.onPlayPressed]: native start then [PlaybackSessionController.markPlayingAndStartCompletionMonitor]. */
    private suspend fun TestScope.armPlaybackMonitor(audio: PlaybackSessionTestAudio, sut: PlaybackSessionController) {
        assertTrue(audio.startPlayback(engineSpec()))
        sut.markPlayingAndStartCompletionMonitor("a")
        advanceUntilIdle()
    }

    @Test
    fun `onSelectionChangedDuringPlayback is deprecated no-op while session active`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { emptyList() },
                )
            armPlaybackMonitor(audio, sut)
            sut.onSelectionChangedDuringPlayback(setOf("a", "b"), emptyList())
            advanceUntilIdle()
            assertEquals(1, audio.startPlaybackCalls)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `onSelectionChangedDuringPlayback is no-op when playback is idle`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = PlaybackSessionTestAudio()
        val sut = PlaybackSessionController(
            scope = this,
            audioController = audio,
            dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
            loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
            currentProjectId = { PROJECT_ID },
            visibleTracks = { emptyList() },
        )
        sut.onSelectionChangedDuringPlayback(setOf("a"), emptyList())
        advanceUntilIdle()
        org.junit.Assert.assertNull(audio.lastArmedLaneAudibility)
    }

    @Test
    fun `playback completion clears playing ids when loop is off`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = PlaybackSessionTestAudio()
        val visible = listOf(track("a", loop = false))
        val sut = PlaybackSessionController(
            scope = this,
            audioController = audio,
            dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
            loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
            currentProjectId = { PROJECT_ID },
            visibleTracks = { visible },
        )
        armPlaybackMonitor(audio, sut)
        assertEquals(setOf("a"), sut.sessionTrackIds.value)
        assertEquals("a", sut.sessionLaneTrackIdsForTests()[0])
        audio.finishPlaybackPulse()
        advanceUntilIdle()
        assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
        assertTrue(sut.sessionLaneTrackIdsForTests().all { it == null })
        assertFalse(sut.hasActivePlaybackSession())
        assertEquals(0, sut.hotJoinMonitorCountForTests())
    }

    @Test
    fun `playback completion tears down session when native stops with loop lane`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible = listOf(track("a", loop = true))
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { visible },
                )
            armPlaybackMonitor(audio, sut)
            assertEquals(setOf("a"), sut.sessionTrackIds.value)
            audio.finishPlaybackPulse()
            advanceUntilIdle()
            assertEquals(1, audio.startPlaybackCalls)
            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
            assertFalse(sut.hasActivePlaybackSession())
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `mixed loop and non-loop completion does not restart whole session`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible =
                listOf(
                    track("a", loop = false, wav = "a.wav"),
                    track("b", loop = true, wav = "b.wav"),
                    track("c", loop = true, wav = "c.wav"),
                )
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { visible },
                )

            val spec =
                projectFix.toMultiPlaybackSpec(visible.filter { it.id in setOf("a", "b") })
                    ?: error("expected multi spec")
            assertTrue(audio.startPlayback(spec))
            sut.markPlayingAndStartCompletionMonitor(listOf("a", "b"))
            advanceUntilIdle()
            audio.finishPlaybackPulse()
            advanceUntilIdle()

            assertEquals(1, audio.startPlaybackCalls)
            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `completion monitor preserves session while native keeps playing with two loop lanes`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible =
                listOf(
                    track("a", loop = true, wav = "a.wav"),
                    track("b", loop = true, wav = "b.wav"),
                )
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { visible },
                )
            val spec =
                projectFix.toMultiPlaybackSpec(visible)
                    ?: error("expected multi spec")
            assertTrue(audio.startPlayback(spec))
            sut.markPlayingAndStartCompletionMonitor(listOf("a", "b"))
            advanceUntilIdle()

            assertEquals(setOf("a", "b"), sut.sessionTrackIds.value)
            assertTrue(sut.hasActivePlaybackSession())
            assertEquals(1, audio.startPlaybackCalls)

            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `playback completion clears playing when native stops for loop lane`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible = listOf(track("a", loop = true))
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { visible },
                )
            armPlaybackMonitor(audio, sut)
            audio.finishPlaybackPulse()
            advanceUntilIdle()
            assertEquals(1, audio.startPlaybackCalls)
            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
        }

    @Test
    fun `transport teardown methods clear playing ids and mute completion handling`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible = listOf(track("a", loop = true))
            val sut = PlaybackSessionController(
                scope = this,
                audioController = audio,
                dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
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
            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
        }

    @Test
    fun `playback completion during recording clears playing without transport completion`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            var transportCompleted = false
            val visible = listOf(track("a", loop = false))
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { visible },
                    onPlaybackCompleted = { transportCompleted = true },
                    suppressTransportOnPlaybackCompletion = { true },
                )
            armPlaybackMonitor(audio, sut)
            assertEquals("a", sut.sessionLaneTrackIdsForTests()[0])
            audio.finishPlaybackPulse()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
            assertEquals(false, transportCompleted)
            assertTrue(sut.sessionLaneTrackIdsForTests().all { it == null })
            assertFalse(sut.hasActivePlaybackSession())
            assertEquals(0, sut.hotJoinMonitorCountForTests())
        }

    @Test
    fun `playback completion invokes transport completion callback`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = PlaybackSessionTestAudio()
        var transportCompleted = false
        val sut =
            PlaybackSessionController(
                scope = this,
                audioController = audio,
                dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                currentProjectId = { PROJECT_ID },
                visibleTracks = { listOf(track("a", loop = false)) },
                onPlaybackCompleted = { transportCompleted = true },
            )
        armPlaybackMonitor(audio, sut)
        audio.finishPlaybackPulse()
        advanceUntilIdle()
        assertTrue(transportCompleted)
    }

    @Test
    fun `selection after playback completion does not touch native audibility`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { emptyList() },
                )
            armPlaybackMonitor(audio, sut)
            audio.finishPlaybackPulse()
            advanceUntilIdle()
            val audibilityCallsBefore = audio.armedLaneAudibilityCalls

            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), track("b", loop = false, wav = "b.wav")),
            )
            advanceUntilIdle()

            assertEquals(audibilityCallsBefore, audio.armedLaneAudibilityCalls)
            assertEquals(0, audio.beginHotJoinCalls)
        }

    @Test
    fun `selection after overdub backing completion does not touch native audibility`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { listOf(track("a", loop = false)) },
                    suppressTransportOnPlaybackCompletion = { true },
                )
            armPlaybackMonitor(audio, sut)
            audio.finishPlaybackPulse()
            advanceUntilIdle()
            val audibilityCallsBefore = audio.armedLaneAudibilityCalls

            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a"),
                playableTracks = listOf(track("a", loop = false)),
            )
            advanceUntilIdle()

            assertEquals(audibilityCallsBefore, audio.armedLaneAudibilityCalls)
        }

    @Test
    fun `clearPlayingTransportState clears lane maps`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { emptyList() },
                )
            armPlaybackMonitor(audio, sut)
            sut.clearPlayingTransportState()
            advanceUntilIdle()

            assertTrue(sut.sessionLaneTrackIdsForTests().all { it == null })
            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
            assertFalse(sut.hasActivePlaybackSession())
        }

    @Test
    fun `restartEngineFromPlayhead rebuilds sessionTrackIds and lane maps from spec`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { emptyList() },
                )
            val trackA = track("a", loop = false, wav = "a.wav")
            val trackB = track("b", loop = false, wav = "b.wav")
            val armSpec =
                projectFix.toMultiPlaybackSpec(listOf(trackA, trackB))
                    ?: error("expected multi spec")
            assertTrue(audio.startPlayback(armSpec))
            sut.markPlayingAndStartCompletionMonitor(listOf("a", "b"))
            advanceUntilIdle()
            assertEquals(setOf("a", "b"), sut.sessionTrackIds.value)

            sut.pauseEnginePreservingSession()
            val restartSpec = armSpec.copy(startPositionMs = 4_000L)
            assertTrue(
                sut.restartEngineFromPlayhead(
                    restartSpec,
                    listOf("a", "b"),
                    setOf("a", "b"),
                ),
            )
            advanceUntilIdle()

            assertEquals(setOf("a", "b"), sut.sessionTrackIds.value)
            assertEquals(listOf("a", "b"), sut.sessionLaneTrackIdsForTests().filterNotNull())
            assertEquals(2, audio.lastMultiPlaybackSpec?.lanes?.size)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `teardown clears sessionTrackIds and sessionLaneTrackIds`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible = listOf(track("a", loop = false))
            val sut =
                PlaybackSessionController(
                    scope = this,
                    audioController = audio,
                    dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { visible },
                )
            armPlaybackMonitor(audio, sut)
            audio.finishPlaybackPulse()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
            assertTrue(sut.sessionLaneTrackIdsForTests().all { it == null })
            assertFalse(sut.hasActivePlaybackSession())
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
    private val hotJoinLifecycleByLane: Map<Int, List<PlaybackLaneLifecycle>> = emptyMap(),
) : AudioController {
    var startPlaybackCalls = 0
        private set

    var stopPlaybackCalls = 0
        private set

    var lastMultiPlaybackSpec: MultiPlaybackSpec? = null
        private set

    private var startPlaybackInvocationIndex = 0

    private val _playbackState = MutableStateFlow(false)
    override val playbackState: StateFlow<Boolean> = _playbackState.asStateFlow()
    override val recordingInputLevel: StateFlow<Float> = MutableStateFlow(0f)

    override fun readMasterPeakHoldLinear(): Float = 0f

    override fun resetMasterPeakHold() = Unit

    var transportPositionMsValue: Long = 0L

    override fun transportPositionMs(): Long = transportPositionMsValue

    override fun isPlaybackEngineRunning(): Boolean = _playbackState.value

    fun finishPlaybackPulse() {
        _playbackState.value = false
    }

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? = null

    override fun stopRecording(): Boolean = true

    override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
        startPlaybackCalls += 1
        lastMultiPlaybackSpec = spec
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun setPlaybackLaneGain(laneIndex: Int, gain: Float) = Unit

    override fun setPlaybackLanePan(laneIndex: Int, pan: Float) = Unit

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

    var lastHotJoinClipStartMs: Long = 0L
    var lastHotJoinClipDurationMs: Long = 0L
    var lastHotJoinLoopEnabled: Boolean = false
    var lastHotJoinLoopSourceStartMs: Long = 0L
    var lastHotJoinLoopSourceEndMs: Long = 0L

    override fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
        loopEnabled: Boolean,
        loopSourceStartMs: Long,
        loopSourceEndMs: Long,
        pan: Float,
    ): Int {
        beginHotJoinCalls += 1
        lastHotJoinClipStartMs = timelineClipStartMs
        lastHotJoinClipDurationMs = timelineClipDurationMs
        lastHotJoinLoopEnabled = loopEnabled
        lastHotJoinLoopSourceStartMs = loopSourceStartMs
        lastHotJoinLoopSourceEndMs = loopSourceEndMs
        return 1
    }

    override fun cancelHotJoinLane(laneIndex: Int) = Unit

    private val hotJoinLifecyclePollCount = mutableMapOf<Int, Int>()

    override fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle {
        val sequence = hotJoinLifecycleByLane[laneIndex] ?: return PlaybackLaneLifecycle.Inactive
        val pollIndex = hotJoinLifecyclePollCount.getOrPut(laneIndex) { 0 }
        hotJoinLifecyclePollCount[laneIndex] = pollIndex + 1
        return sequence.getOrElse(pollIndex) { sequence.last() }
    }

    override fun stopPlayback(): Boolean {
        stopPlaybackCalls += 1
        _playbackState.value = false
        return stopPlaybackResult
    }

    override fun release() {
        _playbackState.value = false
    }
}
