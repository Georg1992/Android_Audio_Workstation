package com.georgv.audioworkstation.core.audio.capability.audit

import com.georgv.audioworkstation.core.audio.capability.ResolvedAudioCapability
import com.georgv.audioworkstation.core.audio.capability.sampleCapabilityProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityCompletenessEvaluatorTest {
    @Test
    fun evaluate_fullProfile_isComplete() {
        val profile =
            sampleCapabilityProfile().copy(
                startup =
                    sampleCapabilityProfile().startup.copy(
                        startupMetricsUpdatedAt = System.currentTimeMillis(),
                    ),
            )
        val result = CapabilityCompletenessEvaluator.evaluate(profile)

        assertTrue(result.outputStreamConfigCaptured)
        assertTrue(result.inputStreamConfigCaptured)
        assertTrue(result.outputHardwareFloorCaptured)
        assertTrue(result.trueCaptureDelayCaptured)
        assertTrue(result.roundTripCaptured)
        assertTrue(result.jitterCaptured)
        assertTrue(result.startupMetricsCaptured)
        assertEquals("none", result.missingFieldsLabel)
    }

    @Test
    fun evaluate_nullProfile_listsMissingFields() {
        val result = CapabilityCompletenessEvaluator.evaluate(null)

        assertFalse(result.outputStreamConfigCaptured)
        assertTrue(result.missingFields.contains("profile"))
    }
}
