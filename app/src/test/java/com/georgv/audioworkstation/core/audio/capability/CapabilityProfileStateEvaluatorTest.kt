package com.georgv.audioworkstation.core.audio.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class CapabilityProfileStateEvaluatorTest {
    @Test
    fun evaluate_validatedProfile_returnsValidated() {
        val profile = sampleCapabilityProfile()
        val validation = CapabilityProfileValidator.validate(profile)
        val state =
            CapabilityProfileStateEvaluator.evaluate(
                profile = profile,
                validation = validation,
                routeUnchanged = true,
            )
        assertEquals(CapabilityProfileState.VALIDATED, state)
    }

    @Test
    fun evaluate_routeChanged_returnsPartialOrMeasured() {
        val profile = sampleCapabilityProfile()
        val validation = CapabilityProfileValidator.validate(profile)
        val state =
            CapabilityProfileStateEvaluator.evaluate(
                profile = profile,
                validation = validation,
                routeUnchanged = false,
            )
        assertEquals(CapabilityProfileState.MEASURED, state)
    }

    @Test
    fun evaluate_nullProfile_returnsEmpty() {
        assertEquals(
            CapabilityProfileState.EMPTY,
            CapabilityProfileStateEvaluator.evaluate(null, null, routeUnchanged = true),
        )
    }
}
