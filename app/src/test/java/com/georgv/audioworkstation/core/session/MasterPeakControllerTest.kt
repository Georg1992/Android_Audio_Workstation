package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.core.audio.MasterPeakIndicatorLevel
import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import com.georgv.audioworkstation.ui.screens.projects.ProjectUiState
import com.georgv.audioworkstation.ui.screens.projects.buildProjectRealtimeUiState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MasterPeakControllerTest {

    @Test
    fun `polls native peak hold while playing`() = runTest {
        val audio = FakeMasterPeakAudioController()
        val phase = MutableStateFlow(TransportPlaybackPhase.Idle)
        val warnings = mutableListOf<Unit>()
        val controller =
            MasterPeakController(
                scope = backgroundScope,
                meter = audio,
                dispatchers = TestAppDispatchers.unified(StandardTestDispatcher(testScheduler)),
                transportPhase = phase,
                onOverloadWarning = { warnings.add(Unit) },
            )

        phase.value = TransportPlaybackPhase.Playing
        runCurrent()
        audio.masterPeakHoldLinearValue = 0.316f
        advanceTimeBy(MasterPeakController.MASTER_PEAK_HOLD_POLL_MS + 1)
        runCurrent()

        assertEquals(0.316f, controller.peakHoldLinear.value, 0.0001f)
        assertTrue(warnings.isEmpty())
    }

    @Test
    fun `reset clears display and native hold`() = runTest {
        val audio = FakeMasterPeakAudioController()
        val phase = MutableStateFlow(TransportPlaybackPhase.Playing)
        val controller =
            MasterPeakController(
                scope = backgroundScope,
                meter = audio,
                dispatchers = TestAppDispatchers.unified(StandardTestDispatcher(testScheduler)),
                transportPhase = phase,
                onOverloadWarning = {},
            )
        runCurrent()
        audio.masterPeakHoldLinearValue = 0.5f
        advanceTimeBy(MasterPeakController.MASTER_PEAK_HOLD_POLL_MS + 1)
        runCurrent()

        controller.resetDisplayAndNativeHold()
        runCurrent()

        assertEquals(0f, controller.peakHoldLinear.value, 0.0001f)
        assertEquals(0f, audio.masterPeakHoldLinearValue, 0.0001f)
    }

    @Test
    fun `overload warning emits once per session until reset`() = runTest {
        val audio = FakeMasterPeakAudioController()
        val phase = MutableStateFlow(TransportPlaybackPhase.Playing)
        val warnings = mutableListOf<Unit>()
        val controller =
            MasterPeakController(
                scope = backgroundScope,
                meter = audio,
                dispatchers = TestAppDispatchers.unified(StandardTestDispatcher(testScheduler)),
                transportPhase = phase,
                onOverloadWarning = { warnings.add(Unit) },
            )
        runCurrent()

        audio.masterPeakHoldLinearValue = 2.5f
        advanceTimeBy(MasterPeakController.MASTER_PEAK_HOLD_POLL_MS + 1)
        runCurrent()
        assertEquals(1, warnings.size)

        audio.masterPeakHoldLinearValue = 3.0f
        advanceTimeBy(MasterPeakController.MASTER_PEAK_HOLD_POLL_MS + 1)
        runCurrent()
        assertEquals(1, warnings.size)

        controller.resetDisplayAndNativeHold()
        runCurrent()
        audio.masterPeakHoldLinearValue = 2.5f
        advanceTimeBy(MasterPeakController.MASTER_PEAK_HOLD_POLL_MS + 1)
        runCurrent()
        assertEquals(2, warnings.size)
    }

    @Test
    fun `realtime builder shows held peak while paused`() {
        val structural =
            ProjectUiState(
                playbackSessionActive = true,
                sessionTrackIds = setOf("a"),
            )
        val playing =
            buildProjectRealtimeUiState(
                playheadMs = 0L,
                mixPlayheadMs = 0L,
                recordingLevel = 0f,
                peakHoldLinear = 0.316f,
                structural = structural,
                transportPhase = TransportPlaybackPhase.Playing,
            )
        val paused =
            buildProjectRealtimeUiState(
                playheadMs = 0L,
                mixPlayheadMs = 0L,
                recordingLevel = 0f,
                peakHoldLinear = 0.316f,
                structural = structural,
                transportPhase = TransportPlaybackPhase.Paused,
            )
        assertEquals("-10.0 dB", playing.masterPeakDbText)
        assertEquals(MasterPeakIndicatorLevel.Green, playing.masterPeakIndicatorLevel)
        assertEquals(playing.masterPeakDbText, paused.masterPeakDbText)
        assertEquals(playing.masterPeakIndicatorLevel, paused.masterPeakIndicatorLevel)
    }
}

private class FakeMasterPeakAudioController : MeterPort {
    var masterPeakHoldLinearValue = 0f
    override val recordingInputLevel = MutableStateFlow(0f)

    override fun readMasterPeakHoldLinear(): Float = masterPeakHoldLinearValue

    override fun resetMasterPeakHold() {
        masterPeakHoldLinearValue = 0f
    }

    override fun transportPositionMs(): Long = 0L
    override fun transportStartFrame(): Long = 0L
    override fun transportFrame(): Long = 0L
    override fun liveOutputLatencyNs(): Long = PlaybackTransportSync.LiveOutputLatencyUnsetNs
}
