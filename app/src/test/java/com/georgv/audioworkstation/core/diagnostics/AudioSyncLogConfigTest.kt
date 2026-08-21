package com.georgv.audioworkstation.core.diagnostics

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioSyncLogConfigTest {
    @Test
    fun `clock validation mode defaults enabled`() {
        assertTrue(AudioSyncLogConfig.clockValidationMode)
    }

    @Test
    fun `verbose diagnostics default disabled for investigation phase`() {
        assertFalse(AudioSyncLogConfig.detailedStartupLogsEnabled)
        assertFalse(AudioSyncLogConfig.rawTimestampSpamEnabled)
        assertFalse(AudioSyncLogConfig.laneMapLogsEnabled)
        assertFalse(AudioSyncLogConfig.transportFrameVerboseEnabled)
    }
}
