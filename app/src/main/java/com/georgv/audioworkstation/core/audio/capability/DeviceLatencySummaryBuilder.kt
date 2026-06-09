package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.capability.audit.AppLatencyAuditBuilder
import com.georgv.audioworkstation.core.audio.capability.audit.CapabilityCompletenessEvaluator
import com.georgv.audioworkstation.core.audio.latency.AudioLivePathType
import com.georgv.audioworkstation.engine.NativeEngine

object DeviceLatencySummaryBuilder {
    fun build(
        resolved: ResolvedAudioCapability,
        nativeEngine: NativeEngine? = null,
    ): DeviceLatencySummary {
        val profile = resolved.profile
        val completeness = CapabilityCompletenessEvaluator.evaluate(profile)
        val history = profile?.measurementHistory ?: CapabilityMeasurementHistory()
        val outputStats = history.outputFloorStats()
        val roundTripStats = history.roundTripStats()
        val captureStats = history.captureDelayStats()
        val jitterStats = history.jitterStats()

        val appAudit =
            if (nativeEngine != null && profile != null) {
                AppLatencyAuditBuilder.build(nativeEngine, AudioLivePathType.OVERDUB, profile)
            } else {
                null
            }

        val warnings = buildWarnings(resolved, completeness, profile)
        val missingData = completeness.missingFields
        val dataComplete = missingData.isEmpty() || (missingData.size == 1 && missingData.contains("startup_metrics"))

        return DeviceLatencySummary(
            routeKey = resolved.routeKey,
            sampleRate = resolved.sampleRate,
            profileState = resolved.profileState,
            outputMedianMs = outputStats?.medianMs ?: resolved.outputLatencyMs,
            outputP95Ms = outputStats?.p95Ms ?: resolved.outputLatencyMs,
            inputCaptureMedianMs = captureStats?.medianMs ?: resolved.inputCaptureDelayMs,
            roundTripMedianMs = roundTripStats?.medianMs ?: resolved.roundTripMs,
            jitterMedianMs = jitterStats?.medianMs ?: resolved.jitterMs,
            appAddedOutputP95Ms = appAudit?.appAddedOutputMsEstimate,
            appAddedInputP95Ms = appAudit?.appAddedInputMsEstimate,
            lowLatencyOutputGranted = resolved.lowLatencyOutputPathGranted,
            lowLatencyInputGranted = resolved.lowLatencyInputPathGranted,
            bestKnownBackend = profile?.derived?.recommendedBackend ?: "Unspecified",
            highLatencyRoute = profile?.highLatencyOutputRoute == true,
            dataConfidence = resolved.confidence,
            missingData = missingData,
            warnings = warnings,
            dataComplete = dataComplete,
            profileId = resolved.profileId,
            backendInventory = history.backends,
            completeness = completeness,
        )
    }

    private fun buildWarnings(
        resolved: ResolvedAudioCapability,
        completeness: com.georgv.audioworkstation.core.audio.capability.audit.CapabilityCompletenessResult,
        profile: DeviceAudioCapabilityProfile?,
    ): List<String> {
        val warnings = mutableListOf<String>()
        if (!resolved.lowLatencyOutputPathGranted) {
            warnings += "Low latency denied"
        }
        if (profile?.routeType == AudioRouteType.BLUETOOTH) {
            warnings += "Bluetooth route"
        }
        if (profile?.highLatencyOutputRoute == true || profile?.derived?.outputTier == LatencyTier.HIGH) {
            warnings += "output latency high"
        }
        if (!completeness.trueCaptureDelayCaptured) {
            warnings += "capture delay unknown"
        }
        if (profile?.validation?.measurementInconsistent == true) {
            warnings += "measurement inconsistent"
        }
        return warnings
    }
}
