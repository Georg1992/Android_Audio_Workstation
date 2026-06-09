package com.georgv.audioworkstation.ui.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncDiagTest {
    @Test
    fun `formatMessage prefixes diagnostic category`() {
        assertEquals(
            "[LATENCY_BUDGET] outputHalMs=88 inputHalMs=74",
            AudioSyncDiag.formatMessage(
                AudioSyncDiag.Prefix.LATENCY_BUDGET,
                "outputHalMs=88 inputHalMs=74",
            ),
        )
        assertEquals(
            "[OVERDUB] REC_PRESS uiPlayheadMs=0",
            AudioSyncDiag.formatMessage(
                AudioSyncDiag.Prefix.OVERDUB,
                "REC_PRESS uiPlayheadMs=0",
            ),
        )
        assertEquals(
            "[PLAYBACK_LATENCY] playbackLatencyMs=45",
            AudioSyncDiag.formatMessage(
                AudioSyncDiag.Prefix.PLAYBACK_LATENCY,
                "playbackLatencyMs=45",
            ),
        )
    }

    @Test
    fun `shouldLog keeps clock validation essentials enabled`() {
        assertTrue(AudioSyncDiag.shouldLog(AudioSyncDiag.Prefix.LATENCY_BUDGET))
        assertTrue(AudioSyncDiag.shouldLog(AudioSyncDiag.Prefix.DEVICE_LATENCY_BUDGET))
        assertTrue(AudioSyncDiag.shouldLog(AudioSyncDiag.Prefix.AUDIO_STREAM_CONFIGURATION))
        assertFalse(AudioSyncDiag.shouldLog(AudioSyncDiag.Prefix.PLAYBACK_STARTUP_BREAKDOWN))
        assertFalse(AudioSyncDiag.shouldLog(AudioSyncDiag.Prefix.OVERDUB))
    }

    @Test
    fun `tag is AudioSyncDiag`() {
        assertEquals("AudioSyncDiag", AudioSyncDiag.TAG)
    }
}
