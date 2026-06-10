package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineAudiblePlayheadTest {
    @Test
    fun `mix transport unchanged when output latency is zero`() {
        assertEquals(500L, mixTransportToAudiblePlayheadMs(500L, 0.0))
        assertEquals(500L, audiblePlayheadToMixTransportMs(500L, 0.0))
    }

    @Test
    fun `audible playhead subtracts session output latency`() {
        assertEquals(416L, mixTransportToAudiblePlayheadMs(500L, 84.0))
    }

    @Test
    fun `mix transport adds session output latency for seeks`() {
        assertEquals(584L, audiblePlayheadToMixTransportMs(500L, 84.0))
    }
}
