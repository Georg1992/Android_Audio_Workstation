package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTransportSyncTest {

    @Test
    fun `effective output latency uses live HAL when valid`() {
        val controller = FakeLatencyAudioController(liveOutputLatencyNs = 84_000_000L)
        assertEquals(84.0, PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(controller), 0.001)
    }

    @Test
    fun `effective output latency is zero when live HAL unset`() {
        val controller = FakeLatencyAudioController(liveOutputLatencyNs = PlaybackTransportSync.LiveOutputLatencyUnsetNs)
        assertEquals(0.0, PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(controller), 0.001)
    }

    @Test
    fun `mix transport adds live output latency to audible playhead`() {
        val controller = FakeLatencyAudioController(liveOutputLatencyNs = 100_000_000L)
        assertEquals(
            500L,
            PlaybackTransportSync.mixTransportMs(controller, AudibleMs(400L)).value,
        )
    }

    @Test
    fun `audible playhead subtracts live output latency from mix transport`() {
        val controller = FakeLatencyAudioController(liveOutputLatencyNs = 50_000_000L)
        assertEquals(
            450L,
            PlaybackTransportSync.audiblePlayheadMs(controller, MixTransportMs(500L)).value,
        )
    }

    @Test
    fun `withAudibleStartPositionMs converts start position for native arm`() {
        val spec =
            MultiPlaybackSpec(
                sampleRate = 48_000,
                lanes =
                    listOf(
                        TrackPlaybackLane(
                            trackId = "a",
                            wavFilePath = "a.wav",
                            gain = 1f,
                        ),
                    ),
                startPositionMs = 0L,
            )
        val armed = spec.withAudibleStartPositionMs(audibleStartMs = 200L, outputLatencyMs = 84.0)
        assertEquals(284L, armed.startPositionMs)
    }

    @Test
    fun `requirePreparedCapability fails when gate has no capability`() {
        val gate =
            object : SessionTransportCapabilityGate {
                override suspend fun prepareForLiveSession(sampleRate: Int): ResolvedAudioCapability =
                    error("unused")

                override fun ensurePreparedForSampleRate(sampleRate: Int) = Unit

                override fun lastPreparedCapability(): ResolvedAudioCapability? = null
            }
        val error =
            runCatching { PlaybackTransportSync.requirePreparedCapability(gate) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    private class FakeLatencyAudioController(
        private val liveOutputLatencyNs: Long,
    ) : AudioController {
        override val playbackState = MutableStateFlow(false)
        override val recordingInputLevel = MutableStateFlow(0f)

        override fun liveOutputLatencyNs(): Long = liveOutputLatencyNs

        override fun readMasterPeakHoldLinear(): Float = 0f

        override fun resetMasterPeakHold() = Unit

        override fun transportPositionMs(): Long = 0L

        override fun isPlaybackEngineRunning(): Boolean = false

        override fun startRecording(spec: RecordingSpec, outputPath: String?): String? = null

        override fun stopRecording(): Boolean = true

        override fun startPlayback(spec: MultiPlaybackSpec): Boolean = true

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
}
