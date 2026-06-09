package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioTimelineMapperTest {

    @Test
    fun `transportFrameToMs matches native integer division`() {
        assertEquals(0L, AudioTimelineMapper.transportFrameToMs(0L, 48_000))
        assertEquals(1_000L, AudioTimelineMapper.transportFrameToMs(48_000L, 48_000))
        assertEquals(500L, AudioTimelineMapper.transportFrameToMs(24_000L, 48_000))
        assertEquals(20L, AudioTimelineMapper.transportFrameToMs(1_000L, 48_000))
        assertEquals(0L, AudioTimelineMapper.transportFrameToMs(47L, 48_000))
        assertEquals(0L, AudioTimelineMapper.transportFrameToMs(100L, 0))
    }

    @Test
    fun `transportMsToFrame matches native playbackStartFrameFromMs`() {
        assertEquals(0L, AudioTimelineMapper.transportMsToFrame(0L, 48_000))
        assertEquals(0L, AudioTimelineMapper.transportMsToFrame(-1L, 48_000))
        assertEquals(48_000L, AudioTimelineMapper.transportMsToFrame(1_000L, 48_000))
        assertEquals(24_000L, AudioTimelineMapper.transportMsToFrame(500L, 48_000))
        assertEquals(960L, AudioTimelineMapper.transportMsToFrame(20L, 48_000))
        assertEquals(0L, AudioTimelineMapper.transportMsToFrame(20L, 0))
    }

    @Test
    fun `frame and ms conversion round trip for whole second boundaries`() {
        val sampleRate = 44_100
        val ms = 5_000L
        val frame = AudioTimelineMapper.transportMsToFrame(ms, sampleRate)
        assertEquals(ms, AudioTimelineMapper.transportFrameToMs(frame, sampleRate))
    }
}
