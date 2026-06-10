package com.georgv.audioworkstation.core.audio.latency

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputRenderAheadTest {
    private val anchor =
        TransportClockAnchor(
            transportStartFrame = 0L,
            monotonicStartNs = 1_000_000_000L,
            sampleRateHz = 48_000,
        )

    @Test
    fun `latencyFramesFromNs matches anchor frame delta`() {
        val latencyNs = latencyMsToNs(10.0)
        assertEquals(480L, latencyFramesFromNs(latencyNs, 48_000))
        assertEquals(
            anchor.transportFrameAt(1_000_000_000L + latencyNs) - anchor.transportFrameAt(1_000_000_000L),
            latencyFramesFromNs(latencyNs, 48_000),
        )
    }

    @Test
    fun `effectiveOutputLatencyNs uses live HAL only`() {
        assertEquals(12_000_000L, effectiveOutputLatencyNs(12_000_000L, true, 5_000_000L))
        assertEquals(0L, effectiveOutputLatencyNs(0L, false, 5_000_000L))
        assertEquals(0L, effectiveOutputLatencyNs(0L, false, 0L))
    }

    @Test
    fun `mixTransportFrameAtCallback advances mix frame by output latency`() {
        val callbackNs = 1_000_000_000L
        val latencyNs = latencyMsToNs(10.0)
        val mixFrame =
            mixTransportFrameAtCallback(
                callbackMonotonicNs = callbackNs,
                effectiveLatencyNs = latencyNs,
                anchor = anchor,
                offlineTransportFrameFallback = 0L,
                sampleRateHz = 48_000,
            )
        assertEquals(anchor.transportFrameAt(callbackNs + latencyNs), mixFrame)
        assertEquals(480L, mixFrame)
    }

    @Test
    fun `mixTransportFrameAtCallback without latency uses transport now`() {
        val callbackNs = 1_010_000_000L
        val mixFrame =
            mixTransportFrameAtCallback(
                callbackMonotonicNs = callbackNs,
                effectiveLatencyNs = 0L,
                anchor = anchor,
                offlineTransportFrameFallback = 0L,
                sampleRateHz = 48_000,
            )
        assertEquals(anchor.transportFrameAt(callbackNs), mixFrame)
    }
}
