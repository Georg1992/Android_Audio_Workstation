package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

/** JVM math parity for native [transportFrame] → ms (Clock.2). */
class MasterPlaybackTimelineTest {

    @Test
    fun `transport frame to ms matches native integer division at 48kHz`() {
        assertEquals(0L, transportFrameToMs(0L, 48_000))
        assertEquals(30_000L, transportFrameToMs(1_440_000L, 48_000))
        assertEquals(31_000L, transportFrameToMs(1_488_000L, 48_000))
    }

    @Test
    fun `transport frame to ms returns zero for invalid sample rate`() {
        assertEquals(0L, transportFrameToMs(1000L, 0))
        assertEquals(0L, transportFrameToMs(1000L, -1))
    }

    @Test
    fun `start offset example thirty seconds at forty four one kHz`() {
        val sampleRate = 44_100
        val startFrame = (sampleRate.toLong() * 30_000L) / 1000L
        assertEquals(30_000L, transportFrameToMs(startFrame, sampleRate))
    }

    @Test
    fun `masterPlaybackFrameToMs alias matches transportFrameToMs`() {
        val frame = 1_488_000L
        val rate = 48_000
        assertEquals(transportFrameToMs(frame, rate), masterPlaybackFrameToMs(frame, rate))
    }
}
