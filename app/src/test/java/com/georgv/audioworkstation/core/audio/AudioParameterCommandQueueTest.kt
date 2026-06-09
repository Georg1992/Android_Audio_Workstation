package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioParameterCommandQueueTest {

    private class RecordingAudioController : AudioController by FakeMinimalAudioController() {
        val gainCalls = mutableListOf<Pair<Int, Float>>()
        val panCalls = mutableListOf<Pair<Int, Float>>()

        override fun setPlaybackLaneGain(laneIndex: Int, gain: Float) {
            gainCalls.add(laneIndex to gain)
        }

        override fun setPlaybackLanePan(laneIndex: Int, pan: Float) {
            panCalls.add(laneIndex to pan)
        }
    }

    @Test
    fun rapidGainUpdates_coalesceToLatestValue() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val audioParam = StandardTestDispatcher(testScheduler)
        val dispatchers = TestAppDispatchers.withSeparateAudioParam(main, audioParam)
        val session = AudioEngineSession(dispatchers)
        val controller = RecordingAudioController()
        session.acquire()
        val queue = AudioParameterCommandQueue(controller, dispatchers, session)

        queue.setLaneGain(0, 0.1f)
        queue.setLaneGain(0, 0.2f)
        queue.setLaneGain(0, 0.9f)
        advanceUntilIdle()

        assertEquals(listOf(0 to 0.9f), controller.gainCalls)
    }

    @Test
    fun enqueueAfterSessionReleaseIsIgnored() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val audioParam = StandardTestDispatcher(testScheduler)
        val dispatchers = TestAppDispatchers.withSeparateAudioParam(main, audioParam)
        val session = AudioEngineSession(dispatchers)
        val controller = RecordingAudioController()
        session.acquire()
        session.release { }
        advanceUntilIdle()
        val queue = AudioParameterCommandQueue(controller, dispatchers, session)
        queue.setLaneGain(0, 0.5f)
        advanceUntilIdle()
        assertTrue(controller.gainCalls.isEmpty())
    }

    @Test
    fun clearPending_preventsApply() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val audioParam = StandardTestDispatcher(testScheduler)
        val dispatchers = TestAppDispatchers.withSeparateAudioParam(main, audioParam)
        val session = AudioEngineSession(dispatchers)
        val controller = RecordingAudioController()
        session.acquire()
        val queue = AudioParameterCommandQueue(controller, dispatchers, session)

        queue.setLaneGain(1, 0.4f)
        queue.clearPending()
        advanceUntilIdle()

        assertTrue(controller.gainCalls.isEmpty())
    }

    @Test
    fun rapidAlternatingGainAndPan_multipleLanes_coalesceToLatestPerKey() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val audioParam = StandardTestDispatcher(testScheduler)
        val dispatchers = TestAppDispatchers.withSeparateAudioParam(main, audioParam)
        val session = AudioEngineSession(dispatchers)
        val controller = RecordingAudioController()
        session.acquire()
        val queue = AudioParameterCommandQueue(controller, dispatchers, session)

        queue.setLaneGain(0, 0.1f)
        queue.setLanePan(0, -1f)
        queue.setLaneGain(1, 0.2f)
        queue.setLanePan(1, 0.5f)
        queue.setLaneGain(0, 0.9f)
        queue.setLanePan(0, 1f)
        queue.setLaneGain(1, 0.8f)
        queue.setLanePan(1, -0.25f)
        advanceUntilIdle()

        assertEquals(setOf(0 to 0.9f, 1 to 0.8f), controller.gainCalls.toSet())
        assertEquals(setOf(0 to 1f, 1 to -0.25f), controller.panCalls.toSet())
    }

    @Test
    fun panUpdatesPreserveLatestValue() = runTest {
        val main = StandardTestDispatcher(testScheduler)
        val audioParam = StandardTestDispatcher(testScheduler)
        val dispatchers = TestAppDispatchers.withSeparateAudioParam(main, audioParam)
        val session = AudioEngineSession(dispatchers)
        val controller = RecordingAudioController()
        session.acquire()
        val queue = AudioParameterCommandQueue(controller, dispatchers, session)

        queue.setLanePan(2, -0.5f)
        queue.setLanePan(2, 0.75f)
        advanceUntilIdle()

        assertEquals(listOf(2 to 0.75f), controller.panCalls)
    }
}

/** Minimal stub — only gain/pan are exercised in this test class. */
private open class FakeMinimalAudioController : AudioController {
    override val playbackState = kotlinx.coroutines.flow.MutableStateFlow(false)
    override val recordingInputLevel = kotlinx.coroutines.flow.MutableStateFlow(0f)
    override fun readMasterPeakHoldLinear(): Float = 0f
    override fun resetMasterPeakHold() = Unit
    override fun transportPositionMs(): Long = 0L
    override fun isPlaybackEngineRunning(): Boolean = false
    override fun startRecording(spec: RecordingSpec, outputPath: String?): String? = null
    override fun stopRecording(): Boolean = true
    override fun startPlayback(spec: MultiPlaybackSpec): Boolean = false
    override fun setPlaybackLaneGain(laneIndex: Int, gain: Float) = Unit
    override fun setPlaybackLanePan(laneIndex: Int, pan: Float) = Unit
    override fun setArmedPlaybackLaneAudibility(audibleByLaneIndex: BooleanArray) = Unit
    override fun setPlaybackLaneAudible(laneIndex: Int, audible: Boolean) = Unit
    override fun beginHotJoinLane(
        wavFilePath: String,
        gain: Float,
        timelineClipStartMs: Long,
        timelineClipDurationMs: Long,
        loopEnabled: Boolean,
        loopSourceStartMs: Long,
        loopSourceEndMs: Long,
        sourceTrimStartMs: Long,
        pan: Float,
    ): Int = -1
    override fun cancelHotJoinLane(laneIndex: Int) = Unit
    override fun playbackLaneLifecycle(laneIndex: Int): PlaybackLaneLifecycle =
        PlaybackLaneLifecycle.Inactive
    override fun stopPlayback(): Boolean = true
    override fun release() = Unit
}
