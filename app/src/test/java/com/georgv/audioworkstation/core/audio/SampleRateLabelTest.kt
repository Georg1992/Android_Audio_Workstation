package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SampleRateLabelTest {

    @Test
    fun `formatSampleRateLabel uses friendly labels for supported rates`() {
        assertEquals("44.1 kHz", formatSampleRateLabel(44_100))
        assertEquals("48 kHz", formatSampleRateLabel(48_000))
    }

    @Test
    fun `formatSampleRateLabel falls back to hz for unsupported rates`() {
        assertEquals("96000 Hz", formatSampleRateLabel(96_000))
    }

    @Test
    fun `isSupportedProjectSampleRate accepts 44_1 and 48 kHz`() {
        assertTrue(isSupportedProjectSampleRate(44_100))
        assertTrue(isSupportedProjectSampleRate(48_000))
        assertFalse(isSupportedProjectSampleRate(96_000))
    }
}
