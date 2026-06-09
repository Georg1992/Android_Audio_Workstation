package com.georgv.audioworkstation.core.audio.latency

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportClockAnchorTest {
    private val anchor =
        TransportClockAnchor(
            transportStartFrame = 4800L,
            monotonicStartNs = 1_000_000_000L,
            sampleRateHz = 48_000,
        )

    @Test
    fun `transportFrameAt maps monotonic elapsed time to frames`() {
        assertEquals(4800L, anchor.transportFrameAt(1_000_000_000L))
        assertEquals(4800L + 480L, anchor.transportFrameAt(1_010_000_000L))
    }

    @Test
    fun `transportMsAt returns elapsed ms from anchor start frame`() {
        assertEquals(0L, anchor.transportMsAt(1_000_000_000L))
        assertEquals(10L, anchor.transportMsAt(1_010_000_000L))
    }

    @Test
    fun `monotonicNsForTransportFrame inverts transportFrameAt`() {
        val frame = 4800L + 960L
        val ns = anchor.monotonicNsForTransportFrame(frame)
        assertEquals(frame, anchor.transportFrameAt(ns))
    }

    @Test
    fun `monotonicNsForTransportMs maps timeline offset from start frame`() {
        val ns = anchor.monotonicNsForTransportMs(20L)
        assertEquals(4800L + 960L, anchor.transportFrameAt(ns))
    }

    @Test
    fun `latencyMsToNs converts positive milliseconds with rounding`() {
        assertEquals(5_000_000L, latencyMsToNs(5.0))
        assertEquals(5_000_001L, latencyMsToNs(5.000001))
        assertEquals(0L, latencyMsToNs(0.0))
    }
}
