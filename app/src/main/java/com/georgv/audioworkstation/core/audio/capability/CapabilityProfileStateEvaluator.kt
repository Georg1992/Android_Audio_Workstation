package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.capability.audit.CapabilityCompletenessEvaluator
import com.georgv.audioworkstation.core.audio.capability.audit.CapabilityCompletenessResult

object CapabilityProfileStateEvaluator {
    private const val MAX_ACCEPTABLE_JITTER_MS = 30.0

    fun evaluate(
        profile: DeviceAudioCapabilityProfile?,
        validation: CapabilityProfileValidationResult?,
        routeUnchanged: Boolean,
    ): CapabilityProfileState {
        if (profile == null) {
            return CapabilityProfileState.EMPTY
        }
        val completeness = CapabilityCompletenessEvaluator.evaluate(profile)
        if (completeness.missingFields.contains("profile")) {
            return CapabilityProfileState.EMPTY
        }
        if (validation?.flags?.measurementInconsistent == true) {
            return CapabilityProfileState.INCONSISTENT
        }
        val hasAnyMeasurement =
            completeness.roundTripCaptured ||
                completeness.outputHardwareFloorCaptured ||
                completeness.trueCaptureDelayCaptured
        if (!hasAnyMeasurement) {
            return CapabilityProfileState.EMPTY
        }
        if (!isValidated(profile, completeness, validation, routeUnchanged)) {
            if (profile.highLatencyOutputRoute || profile.routeType.isHighLatencyRoute()) {
                return CapabilityProfileState.HIGH_LATENCY_ROUTE
            }
            return if (completeness.roundTripCaptured && completeness.outputHardwareFloorCaptured) {
                CapabilityProfileState.MEASURED
            } else {
                CapabilityProfileState.PARTIAL
            }
        }
        if (profile.highLatencyOutputRoute || profile.routeType.isHighLatencyRoute()) {
            return CapabilityProfileState.HIGH_LATENCY_ROUTE
        }
        return CapabilityProfileState.VALIDATED
    }

    fun isValidated(
        profile: DeviceAudioCapabilityProfile,
        completeness: CapabilityCompletenessResult,
        validation: CapabilityProfileValidationResult?,
        routeUnchanged: Boolean,
    ): Boolean {
        if (!routeUnchanged) {
            return false
        }
        if (validation?.flags?.measurementInconsistent == true) {
            return false
        }
        if (!completeness.outputHardwareFloorCaptured) {
            return false
        }
        if (!completeness.roundTripCaptured) {
            return false
        }
        if (!completeness.trueCaptureDelayCaptured) {
            return false
        }
        if (!jitterAcceptable(profile)) {
            return false
        }
        return true
    }

    private fun jitterAcceptable(profile: DeviceAudioCapabilityProfile): Boolean {
        val jitterStats = profile.measurementHistory.jitterStats()
        val jitterMs = profile.calibration.measuredJitterMs
        if (jitterStats != null) {
            val median = jitterStats.medianMs
            return median != null && median.isFinite() && median <= MAX_ACCEPTABLE_JITTER_MS
        }
        if (jitterMs != null && jitterMs.isFinite() && jitterMs >= 0.0) {
            return jitterMs <= MAX_ACCEPTABLE_JITTER_MS
        }
        return profile.measurementHistory.roundTripMs.size >= 2
    }
}
