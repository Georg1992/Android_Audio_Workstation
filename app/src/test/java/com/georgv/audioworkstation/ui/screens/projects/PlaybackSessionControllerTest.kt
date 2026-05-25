package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackLaneLifecycle
import com.georgv.audioworkstation.core.audio.PlaybackSpec
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

    private fun engineSpec() = PlaybackSpec(sampleRate = 48_000, wavFilePath = "a.wav", gain = 1f)

    /** Matches [ProjectViewModel.onPlayPressed]: native start then [PlaybackSessionController.markPlayingAndStartCompletionMonitor]. */
    private suspend fun TestScope.armPlaybackMonitor(audio: PlaybackSessionTestAudio, sut: PlaybackSessionController) {
        assertTrue(audio.startPlayback(engineSpec()))
        sut.markPlayingAndStartCompletionMonitor("a")
        advanceUntilIdle()
    }

    @Test
    fun `syncLiveAudibility maps selection to armed lanes without restarting playback`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val sut = PlaybackSessionController(
                scope = this,
                audioController = audio,
                loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                currentProjectId = { PROJECT_ID },
                visibleTracks = { emptyList() },
            )
            armPlaybackMonitor(audio, sut)
            assertEquals("a", sut.sessionLaneTrackIdsForTests()[0])

            sut.syncLiveAudibilityFromSelection(emptySet())
            advanceUntilIdle()
            assertEquals(1, audio.startPlaybackCalls)
            val muted = audio.lastArmedLaneAudibility
            requireNotNull(muted)
            assertFalse(muted[0])

            sut.syncLiveAudibilityFromSelection(setOf("a"))
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
    fun `syncLiveAudibility is no-op when playback is idle`() = runTest(mainDispatcherRule.dispatcher) {
        val audio = PlaybackSessionTestAudio()
        val sut = PlaybackSessionController(
            scope = this,
            audioController = audio,
            loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
            currentProjectId = { PROJECT_ID },
            visibleTracks = { emptyList() },
        )
        sut.syncLiveAudibilityFromSelection(setOf("a"))
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
        assertEquals(setOf("a"), sut.sessionTrackIds.value)
        audio.finishPlaybackPulse()
        advanceUntilIdle()
        assertEquals(2, audio.startPlaybackCalls)
        assertEquals(setOf("a"), sut.sessionTrackIds.value)
        sut.cancelCompletionMonitorForTransportStop()
    }

    @Test
    fun `playback completion restarts the same playing group when any playing track loops`() =
        runTest(mainDispatcherRule.dispatcher) {
            val audio = PlaybackSessionTestAudio()
            val visible = listOf(
                track("a", loop = false, wav = "a.wav"),
                track("b", loop = true, wav = "b.wav"),
                track("c", loop = true, wav = "c.wav")
            )
            val sut = PlaybackSessionController(
                scope = this,
                audioController = audio,
                loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                currentProjectId = { PROJECT_ID },
                visibleTracks = { visible },
            )

            val spec =
                projectFix.toMultiPlaybackSpec(visible.filter { it.id in setOf("a", "b") })
                    ?: error("expected multi spec")
            assertTrue(audio.startPlayback(spec))
            sut.markPlayingAndStartCompletionMonitor(listOf("a", "b"))
            audio.finishPlaybackPulse()
            advanceUntilIdle()

            assertEquals(setOf("a", "b"), sut.sessionTrackIds.value)
            assertEquals(listOf("a", "b"), audio.lastMultiPlaybackSpec?.lanes?.map { it.trackId })
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
    fun `loop restart refreshes lane mappings and drops hot join state`() =
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

            assertEquals(listOf("a"), sut.sessionLaneTrackIdsForTests().filterNotNull())
            assertEquals(setOf("a"), sut.sessionTrackIds.value)
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
                    loadCurrentProject = { if (it == PROJECT_ID) projectFix else null },
                    currentProjectId = { PROJECT_ID },
                    visibleTracks = { emptyList() },
                )
            armPlaybackMonitor(audio, sut)
            sut.syncLiveAudibilityFromSelection(emptySet())
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

    var transportPositionMsValue: Long = 0L

    override fun transportPositionMs(): Long = transportPositionMsValue

    override fun isPlaybackEngineRunning(): Boolean = _playbackState.value

    fun finishPlaybackPulse() {
        _playbackState.value = false
    }

    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? = null

    override fun stopRecording(): Boolean = true

    override fun startPlayback(spec: PlaybackSpec): Boolean {
        startPlaybackCalls += 1
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun startPlayback(spec: MultiPlaybackSpec): Boolean {
        startPlaybackCalls += 1
        lastMultiPlaybackSpec = spec
        val permitted = startPlaybackPermitted(startPlaybackInvocationIndex++)
        val playing = permitted && startPlaybackResult
        _playbackState.value = playing
        return playing
    }

    override fun setPlaybackGain(gain: Float) = Unit

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

    override fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
    ): Int {
        beginHotJoinCalls += 1
        lastHotJoinClipStartMs = timelineClipStartMs
        lastHotJoinClipDurationMs = timelineClipDurationMs
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
