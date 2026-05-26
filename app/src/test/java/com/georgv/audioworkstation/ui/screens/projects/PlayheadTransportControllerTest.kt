package com.georgv.audioworkstation.ui.screens.projects

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayheadTransportControllerTest {

    @get:Rule
    val mainDispatcherRule = ProjectViewModelMainDispatcherRule()

    private fun TestScope.playheadController(
        playhead: MutableStateFlow<Long>,
        nativeMs: MutableStateFlow<Long> = MutableStateFlow(0L),
        pollIntervalMs: Long = 50L,
    ): PlayheadTransportController =
        PlayheadTransportController(
            scope = this,
            playheadPositionMs = playhead,
            nativeTransportPositionMs = { nativeMs.value },
            pollIntervalMs = pollIntervalMs,
        ).apply {
            nativePollEnabled = false
        }

    @Test
    fun `recording playhead follows native transport position`() =
        runTest(mainDispatcherRule.dispatcher) {
            val playhead = MutableStateFlow(0L)
            val nativeMs = MutableStateFlow(30_000L)
            val sut = playheadController(playhead, nativeMs)
            sut.setTimelineBaseDurationMs(10_000L)

            sut.onRecordingStarted(fromPositionMs = 30_000L)
            assertEquals(TransportPlaybackPhase.Recording, sut.phase.value)
            assertEquals(30_000L, playhead.value)

            nativeMs.value = 35_000L
            sut.setNativeTransportPositionForTests(35_000L)
            assertEquals(35_000L, playhead.value)
        }

    @Test
    fun `playback playhead follows native transport position`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(5_000L)
        val nativeMs = MutableStateFlow(0L)
        val sut = playheadController(playhead, nativeMs)
        sut.setTimelineBaseDurationMs(10_000L)

        sut.onPlaybackStarted(fromPositionMs = 0L)
        assertEquals(0L, playhead.value)

        nativeMs.value = 200L
        sut.setNativeTransportPositionForTests(200L)
        assertEquals(200L, playhead.value)
    }

    @Test
    fun `native poll updates playhead during playback`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(0L)
        val nativeMs = MutableStateFlow(0L)
        val sut =
            PlayheadTransportController(
                scope = this,
                playheadPositionMs = playhead,
                nativeTransportPositionMs = { nativeMs.value },
                pollIntervalMs = 50L,
            )
        sut.setTimelineBaseDurationMs(10_000L)
        sut.onPlaybackStarted(0L)

        nativeMs.value = 180L
        advanceTimeBy(50)
        runCurrent()
        assertTrue(playhead.value >= 180L)

        sut.enterPaused()
    }

    @Test
    fun `enterPaused freezes playhead when native transport resets to zero`() =
        runTest(mainDispatcherRule.dispatcher) {
            val playhead = MutableStateFlow(2_500L)
            val nativeMs = MutableStateFlow(2_500L)
            val sut = playheadController(playhead, nativeMs)
            sut.setTimelineBaseDurationMs(30_000L)
            sut.onPlaybackStarted(fromPositionMs = 2_500L)

            nativeMs.value = 0L
            sut.enterPaused()

            assertEquals(TransportPlaybackPhase.Paused, sut.phase.value)
            assertEquals(2_500L, playhead.value)
        }

    @Test
    fun `pause preserves playhead and stops native poll`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(0L)
        val nativeMs = MutableStateFlow(0L)
        val sut =
            PlayheadTransportController(
                scope = this,
                playheadPositionMs = playhead,
                nativeTransportPositionMs = { nativeMs.value },
                pollIntervalMs = 50L,
            )
        sut.setTimelineBaseDurationMs(10_000L)
        sut.onPlaybackStarted(0L)

        nativeMs.value = 120L
        advanceTimeBy(50)
        runCurrent()

        sut.enterPaused()
        val pausedAt = playhead.value
        nativeMs.value = 9_000L
        advanceTimeBy(500)
        runCurrent()

        assertEquals(TransportPlaybackPhase.Paused, sut.phase.value)
        assertEquals(pausedAt, playhead.value)
        assertTrue(pausedAt > 0L)
    }

    @Test
    fun `stop resets playhead to zero`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(3_000L)
        val sut = playheadController(playhead)
        sut.setTimelineBaseDurationMs(10_000L)
        sut.enterPaused()

        sut.stopAndResetToZero()

        assertEquals(TransportPlaybackPhase.Idle, sut.phase.value)
        assertEquals(0L, playhead.value)
    }

    @Test
    fun `virtual time advance does not move playhead when native transport is static`() =
        runTest(mainDispatcherRule.dispatcher) {
            val playhead = MutableStateFlow(0L)
            val nativeMs = MutableStateFlow(5_000L)
            val sut =
                PlayheadTransportController(
                    scope = this,
                    playheadPositionMs = playhead,
                    nativeTransportPositionMs = { nativeMs.value },
                    pollIntervalMs = 50L,
                )
            sut.setTimelineBaseDurationMs(30_000L)
            sut.onPlaybackStarted(fromPositionMs = 5_000L)

            advanceTimeBy(500)
            runCurrent()
            assertEquals(5_000L, playhead.value)

            sut.enterPaused()
        }

    @Test
    fun `recording phase virtual time advance does not move playhead without native change`() =
        runTest(mainDispatcherRule.dispatcher) {
            val playhead = MutableStateFlow(0L)
            val nativeMs = MutableStateFlow(12_000L)
            val sut =
                PlayheadTransportController(
                    scope = this,
                    playheadPositionMs = playhead,
                    nativeTransportPositionMs = { nativeMs.value },
                    pollIntervalMs = 50L,
                )
            sut.onRecordingStarted(fromPositionMs = 12_000L)

            advanceTimeBy(500)
            runCurrent()
            assertEquals(12_000L, playhead.value)

            sut.stopAndResetToZero()
        }

    @Test
    fun `playhead follows native transport during playback without timeline base clamp`() =
        runTest(mainDispatcherRule.dispatcher) {
            val playhead = MutableStateFlow(0L)
            val sut = playheadController(playhead)
            sut.setTimelineBaseDurationMs(1_000L)
            sut.onPlaybackStarted(0L)
            sut.setNativeTransportPositionForTests(5_000L)
            assertEquals(5_000L, playhead.value)
        }

    @Test
    fun `scrubbing blocked only during recording`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(0L)
        val sut = playheadController(playhead)
        assertTrue(sut.canScrubPlayhead())
        sut.onPlaybackStarted(0L)
        assertTrue(sut.canScrubPlayhead())
        sut.enterPaused()
        assertTrue(sut.canScrubPlayhead())
        sut.onRecordingStarted(0L)
        assertFalse(sut.canScrubPlayhead())
    }

    @Test
    fun `begin playback seek drag is idempotent`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(500L)
        val sut = playheadController(playhead)
        sut.setTimelineBaseDurationMs(10_000L)
        sut.onPlaybackStarted(500L)
        sut.beginPlaybackSeekDrag()
        sut.beginPlaybackSeekDrag()
        assertTrue(sut.isPlaybackSeekDragActive())
    }

    @Test
    fun `playback seek drag stops native poll and resumes on end`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(0L)
        val nativeMs = MutableStateFlow(0L)
        val sut =
            PlayheadTransportController(
                scope = this,
                playheadPositionMs = playhead,
                nativeTransportPositionMs = { nativeMs.value },
            )
        sut.setTimelineBaseDurationMs(10_000L)
        sut.onPlaybackStarted(0L)
        nativeMs.value = 500L
        sut.setNativeTransportPositionForTests(500L)
        assertEquals(500L, playhead.value)

        sut.beginPlaybackSeekDrag()
        assertTrue(sut.isPlaybackSeekDragActive())
        nativeMs.value = 9_000L
        advanceTimeBy(200)
        runCurrent()
        assertEquals(500L, playhead.value)

        sut.setPlayheadDuringSeekDrag(2_500L, 10_000L)
        assertEquals(2_500L, playhead.value)
        assertTrue(sut.endPlaybackSeekDragAndConsumeResume())
        assertFalse(sut.isPlaybackSeekDragActive())

        nativeMs.value = 3_000L
        sut.setNativeTransportPositionForTests(3_000L)
        assertEquals(3_000L, playhead.value)
    }

    @Test
    fun `base duration change clamps stored playhead when not playing`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(9_000L)
        val sut =
            PlayheadTransportController(
                scope = this,
                playheadPositionMs = playhead,
                nativeTransportPositionMs = { 0L },
            )
        sut.setTimelineBaseDurationMs(5_000L)
        assertEquals(5_000L, playhead.value)
    }

    @Test
    fun `abort playback start returns to idle and keeps playhead`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(2_000L)
        val sut = playheadController(playhead)
        sut.setTimelineBaseDurationMs(10_000L)
        sut.onPlaybackStarted(fromPositionMs = 2_000L)
        sut.abortPlaybackStart()
        assertEquals(TransportPlaybackPhase.Idle, sut.phase.value)
        assertEquals(2_000L, playhead.value)
    }

    @Test
    fun `repeated playback start replaces poll job without stacking`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(0L)
        val sut =
            PlayheadTransportController(
                scope = this,
                playheadPositionMs = playhead,
                nativeTransportPositionMs = { 0L },
                pollIntervalMs = 50L,
            )
        sut.onPlaybackStarted(0L)
        sut.stopAndResetToZero()
        sut.onPlaybackStarted(0L)
        advanceTimeBy(50)
        runCurrent()
        sut.stopAndResetToZero()
    }

    @Test
    fun `reset when project changes clears phase and playhead`() = runTest(mainDispatcherRule.dispatcher) {
        val playhead = MutableStateFlow(4_000L)
        val sut = playheadController(playhead)
        sut.setTimelineBaseDurationMs(10_000L)
        sut.onPlaybackStarted(0L)
        sut.resetWhenProjectChanges()
        assertEquals(TransportPlaybackPhase.Idle, sut.phase.value)
        assertEquals(0L, playhead.value)
    }

    @Test
    fun `base duration change during playback does not requantize playhead`() =
        runTest(mainDispatcherRule.dispatcher) {
            val playhead = MutableStateFlow(0L)
            val nativeMs = MutableStateFlow(15_000L)
            val sut = playheadController(playhead, nativeMs)
            sut.setTimelineBaseDurationMs(30_000L)
            sut.onPlaybackStarted(fromPositionMs = 15_000L)
            assertEquals(15_000L, playhead.value)

            sut.setTimelineBaseDurationMs(45_000L)
            assertEquals(15_000L, playhead.value)

            sut.setTimelineBaseDurationMs(10_000L)
            assertEquals(15_000L, playhead.value)
        }

    @Test
    fun `play and record handoff keeps playhead when native advances after backing ends`() =
        runTest(mainDispatcherRule.dispatcher) {
            val playhead = MutableStateFlow(0L)
            val nativeMs = MutableStateFlow(4_000L)
            val sut = playheadController(playhead, nativeMs)
            sut.setTimelineBaseDurationMs(60_000L)

            sut.onRecordingStarted(fromPositionMs = 0L)
            nativeMs.value = 4_000L
            sut.setNativeTransportPositionForTests(4_000L)
            assertEquals(4_000L, playhead.value)

            nativeMs.value = 7_500L
            sut.setNativeTransportPositionForTests(7_500L)
            assertEquals(7_500L, playhead.value)
            assertEquals(TransportPlaybackPhase.Recording, sut.phase.value)
        }
}
