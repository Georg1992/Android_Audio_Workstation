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
    fun `onSelectionChangedDuringPlayback maps selection to armed lanes without restarting playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val sut = PlaybackSessionController(
                scope = this,
                audioController = audio,
                dispatchers = TestAppDispatchers.unified(mainDispatcherRule.dispatcher),
                loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                currentProjectId = { PROJECT_ID },
                visibleTracks = { emptyList() },
            )
            armPlaybackMonitor(audio, sut)
            assertEquals("a", sut.sessionLaneTrackIdsForTests()[0])

            sut.onSelectionChangedDuringPlayback(emptySet(), emptyList())
            advanceUntilIdle()
            assertEquals(1, audio.startPlaybackCalls)
            val muted = audio.lastArmedLaneAudibility
            requireNotNull(muted)
            assertFalse(muted[0])

            sut.onSelectionChangedDuringPlayback(setOf("a"), emptyList())
            advanceUntilIdle()
            val restored = audio.lastArmedLaneAudibility
            requireNotNull(restored)
            assertTrue(restored[0])
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `deselect while preparing mutes pending lane before Kotlin map update`() =
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
            val trackB = track("b", loop = false, wav = "b.wav")
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            assertEquals(listOf(1 to false), audio.playbackLaneAudibleCalls)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `onSelectionChangedDuringPlayback begins hot join for newly selected track`() =
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
            val trackB = track("b", loop = false, wav = "b.wav")
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()
            assertEquals(1, audio.beginHotJoinCalls)
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
    fun `hot join monitor does not update lane map after session epoch advances`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(
                    hotJoinLifecycleByLane =
                        mapOf(1 to listOf(PlaybackLaneLifecycle.Preparing, PlaybackLaneLifecycle.Active)),
                )
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
            val trackB = track("b", loop = false, wav = "b.wav")
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            runCurrent()
            sut.advancePlaybackSessionEpochForTests()
            advanceTimeBy(HOT_JOIN_POLL_MS)
            advanceUntilIdle()

            assertNull(sut.sessionLaneTrackIdsForTests()[1])
            assertEquals(0, sut.hotJoinMonitorCountForTests())
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `native stop clears hot join state for loop session without restart`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(
                    hotJoinLifecycleByLane =
                        mapOf(1 to listOf(PlaybackLaneLifecycle.Active)),
                )
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
            val trackB = track("b", loop = false, wav = "b.wav")
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = true), trackB),
            )
            advanceUntilIdle()
            assertEquals("b", sut.sessionLaneTrackIdsForTests()[1])

            audio.finishPlaybackPulse()
            advanceUntilIdle()

            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
            assertTrue(sut.sessionLaneTrackIdsForTests().all { it == null })
            assertEquals(1, audio.startPlaybackCalls)
            assertEquals(0, sut.hotJoinMonitorCountForTests())
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `clearPlayingTransportState clears lane maps and hot join monitors`() =
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
            val trackB = track("b", loop = false, wav = "b.wav")
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()
            sut.clearPlayingTransportState()
            advanceUntilIdle()

            assertTrue(sut.sessionLaneTrackIdsForTests().all { it == null })
            assertEquals(emptySet<String>(), sut.sessionTrackIds.value)
            assertEquals(0, sut.hotJoinMonitorCountForTests())
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
    fun `sessionTrackIds excludes hot joined only track`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(
                    hotJoinLifecycleByLane =
                        mapOf(1 to listOf(PlaybackLaneLifecycle.Active)),
                )
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
            val trackB = track("b", loop = false, wav = "b.wav")
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()

            assertEquals(setOf("a"), sut.sessionTrackIds.value)
            assertEquals("b", sut.sessionLaneTrackIdsForTests()[1])
            assertEquals(setOf("a", "b"), sut.currentAudibleTrackIds(setOf("a", "b")))
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `hot join passes timeline clip metadata from track entity`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(
                    hotJoinLifecycleByLane =
                        mapOf(1 to listOf(PlaybackLaneLifecycle.Active)),
                ).apply {
                    transportPositionMsValue = 12_000L
                }
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
            val trackB =
                track("b", loop = false, wav = "b.wav").copy(
                    timelineStartOffsetMs = 10_000L,
                    duration = 5_000L,
                )
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()

            assertEquals(10_000L, audio.lastHotJoinClipStartMs)
            assertEquals(5_000L, audio.lastHotJoinClipDurationMs)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `hot join forwards loop metadata for loop track`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(
                    hotJoinLifecycleByLane =
                        mapOf(1 to listOf(PlaybackLaneLifecycle.Active)),
                ).apply {
                    transportPositionMsValue = 35_000L
                }
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
            val trackB =
                track("b", loop = true, wav = "b.wav").copy(
                    timelineStartOffsetMs = 30_000L,
                    duration = 20_000L,
                    loopStartMs = 0L,
                    loopEndMs = 5_000L,
                )
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()

            assertEquals(1, audio.beginHotJoinCalls)
            assertEquals(30_000L, audio.lastHotJoinClipStartMs)
            assertTrue(audio.lastHotJoinLoopEnabled)
            assertEquals(0L, audio.lastHotJoinLoopSourceStartMs)
            assertEquals(5_000L, audio.lastHotJoinLoopSourceEndMs)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `hot join still starts for loop track before clip start`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(
                    hotJoinLifecycleByLane =
                        mapOf(1 to listOf(PlaybackLaneLifecycle.Active)),
                ).apply {
                    transportPositionMsValue = 10_000L
                }
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
            val trackB =
                track("b", loop = true, wav = "b.wav").copy(
                    timelineStartOffsetMs = 30_000L,
                    duration = 20_000L,
                )
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()

            assertEquals(1, audio.beginHotJoinCalls)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `hot join starts before clip start for silent pre clip region`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio =
                PlaybackSessionTestAudio(
                    hotJoinLifecycleByLane =
                        mapOf(1 to listOf(PlaybackLaneLifecycle.Active)),
                ).apply {
                    transportPositionMsValue = 3_000L
                }
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
            val trackB =
                track("b", loop = false, wav = "b.wav").copy(
                    timelineStartOffsetMs = 10_000L,
                    duration = 5_000L,
                )
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()

            assertEquals(1, audio.beginHotJoinCalls)
            assertEquals(10_000L, audio.lastHotJoinClipStartMs)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `hot join not started when transport is past clip end`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio().apply { transportPositionMsValue = 20_000L }
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
            val trackB =
                track("b", loop = false, wav = "b.wav").copy(
                    timelineStartOffsetMs = 10_000L,
                    duration = 5_000L,
                )
            sut.onSelectionChangedDuringPlayback(
                selectedTrackIds = setOf("a", "b"),
                playableTracks = listOf(track("a", loop = false), trackB),
            )
            advanceUntilIdle()

            assertEquals(0, audio.beginHotJoinCalls)
            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `wouldLeaveNoSessionLaneSelected blocks last session lane deselect`() =
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
            sut.markPlayingAndStartCompletionMonitor(listOf("a"))
            advanceUntilIdle()

            assertTrue(sut.wouldLeaveNoSessionLaneSelected(setOf("a"), "a"))
            assertTrue(sut.wouldLeaveNoSessionLaneSelected(setOf("a", "b"), "a"))
            assertFalse(sut.wouldLeaveNoSessionLaneSelected(setOf("a", "b"), "b"))

            sut.cancelCompletionMonitorForTransportStop()
        }

    @Test
    fun `deselected loaded lane remains in sessionLaneTrackIds`() =
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
            sut.onSelectionChangedDuringPlayback(emptySet(), emptyList())
            advanceUntilIdle()

            assertEquals("a", sut.sessionLaneTrackIdsForTests()[0])
            assertEquals(emptySet<String>(), sut.currentAudibleTrackIds(emptySet()))
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
        private const val HOT_JOIN_POLL_MS = 5L
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
