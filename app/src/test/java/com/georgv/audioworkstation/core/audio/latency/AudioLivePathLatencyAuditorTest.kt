package com.georgv.audioworkstation.core.audio.latency

import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe
import com.georgv.audioworkstation.engine.PlaybackSessionTimings
import com.georgv.audioworkstation.engine.SoftwareBufferProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioLivePathLatencyAuditorTest {

    @Test
    fun `overdub breakdown includes app and hardware latency estimates`() {
        val timings =
            PlaybackSessionTimings(
                playbackArmSteadyNs = 1_000_000L,
                firstInputSampleSteadyNs = 50_000_000L,
                firstNonSilentOutputSteadyNs = 60_000_000L,
                firstAudibleOutputSteadyNs = 70_000_000L,
                prerollFrames = 1_440,
                ioBatchFrames = 1_024,
                recordReadFrames = 256,
                playbackArmTransportStartFrame = 0L,
                firstNonSilentTransportFrame = 100L,
                firstAudiblePeakTransportFrame = 200L,
                firstAudiblePeakMicro = 50_000L,
                oboeStreamOpenDoneSteadyNs = 5_000_000L,
                oboeStreamStartDoneSteadyNs = 8_000_000L,
                firstOboeCallbackSteadyNs = 12_000_000L,
            )
        val outputProbe =
            OboeStreamCapabilityProbe(
                sampleRateHz = 48_000,
                channelCount = 2,
                framesPerBurst = 96,
                bufferCapacityInFrames = 1_024,
                bufferSizeInFrames = 512,
                performanceModeActual = 2,
                sharingModeActual = 0,
                audioSessionId = 1,
                audioApi = 2,
                format = 2,
                estimatedStreamLatencyMs = 20.0,
                timestampAvailable = true,
                timestampStable = false,
                blockFrames = 192,
                xRunCount = 0,
            )

        val breakdown =
            AudioLivePathLatencyAuditor.buildBreakdown(
                pathType = AudioLivePathType.OVERDUB,
                routeKey = "builtin_speaker_sr_48000",
                sampleRateHz = 48_000,
                timings = timings,
                outputProbe = outputProbe,
                inputProbe = outputProbe,
                bufferProfile =
                    SoftwareBufferProfile(
                        ringDurationSeconds = 1,
                        prerollWallMs = 30,
                        ioBatchFrames = 1_024,
                        inputReadFrames = 256,
                        ioIdleSleepMs = 4,
                        inputReadTimeoutMs = 100,
                    ),
            )

        assertEquals(AudioLivePathType.OVERDUB, breakdown.pathType)
        assertEquals(4L, breakdown.streamOpenMs)
        assertEquals(49L, breakdown.firstInputMs)
        assertNotNull(breakdown.appAddedLatencyMs)
        assertTrue(breakdown.appAddedLatencyMs!! > 0L)
        assertTrue(breakdown.estimatedHardwareLatencyMs!! > 0L)
    }

    @Test
    fun `buffer audit lists software and hal buffers`() {
        val entries =
            AudioBufferAudit.entries(
                sampleRateHz = 48_000,
                profile =
                    SoftwareBufferProfile(
                        ringDurationSeconds = 1,
                        prerollWallMs = 30,
                        ioBatchFrames = 1_024,
                        inputReadFrames = 256,
                        ioIdleSleepMs = 4,
                        inputReadTimeoutMs = 100,
                    ),
                outputProbe =
                    OboeStreamCapabilityProbe(
                        sampleRateHz = 48_000,
                        channelCount = 2,
                        framesPerBurst = 96,
                        bufferCapacityInFrames = 2_048,
                        bufferSizeInFrames = 512,
                        performanceModeActual = 2,
                        sharingModeActual = 0,
                        audioSessionId = 1,
                        audioApi = 2,
                        format = 2,
                        estimatedStreamLatencyMs = 15.0,
                        timestampAvailable = true,
                        timestampStable = true,
                        blockFrames = 192,
                        xRunCount = 0,
                    ),
                inputProbe = null,
            )

        assertTrue(entries.any { it.name == "lane_ring_prefetch" })
        assertTrue(entries.any { it.name == "input_blocking_read" })
        assertTrue(entries.any { it.name == "oboe_output_buffer" && it.sizeFrames == 512 })
    }
}
