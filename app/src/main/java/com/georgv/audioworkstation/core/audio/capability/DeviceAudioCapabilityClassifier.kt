package com.georgv.audioworkstation.core.audio.capability

object DeviceAudioCapabilityClassifier {
    private const val LOW_LATENCY_MS = 25.0
    private const val MEDIUM_LATENCY_MS = 80.0
    private const val MIN_LATENCY_CONFIDENCE = 0.6

    fun classify(profile: DeviceAudioCapabilityProfile): DerivedCapabilityData {
        val outputMs = effectiveOutputLatencyMs(profile)
        val inputMs = profile.calibration.estimatedTrueCaptureDelayMs
        val outputTier = tierForOutput(outputMs, profile)
        val inputTier = tierForInput(inputMs, profile.input)
        val overall = maxTier(outputTier, inputTier)
        return DerivedCapabilityData(
            outputTier = outputTier,
            inputTier = inputTier,
            overallLiveLatencyTier = overall,
            recommendedBackend = recommendedBackend(profile.output),
        )
    }

    fun effectiveOutputLatencyMs(profile: DeviceAudioCapabilityProfile): Double? {
        val hal = profile.output.halReportedLatencyMs
        if (hal != null && hal.isFinite() && hal >= 0.0) {
            return hal
        }
        val calibrated = profile.calibration.estimatedOutputLatencyMs
        if (calibrated != null && calibrated.isFinite() && calibrated >= 0.0) {
            return calibrated
        }
        return profile.output.bufferSizeMs.takeIf { it > 0.0 }
    }

    private fun tierForOutput(
        latencyMs: Double?,
        profile: DeviceAudioCapabilityProfile,
    ): LatencyTier {
        if (profile.highLatencyOutputRoute || profile.output.lowLatencyPathDenied) {
            return LatencyTier.HIGH
        }
        return tierForLatencyMs(latencyMs)
    }

    private fun tierForInput(
        captureDelayMs: Double?,
        input: StreamCapabilitySide,
    ): LatencyTier {
        if (captureDelayMs == null) {
            return LatencyTier.UNKNOWN
        }
        if (!input.timestampAvailable && input.latencyConfidence < MIN_LATENCY_CONFIDENCE) {
            return LatencyTier.UNKNOWN
        }
        return tierForLatencyMs(captureDelayMs)
    }

    private fun tierForLatencyMs(latencyMs: Double?): LatencyTier {
        if (latencyMs == null || !latencyMs.isFinite() || latencyMs < 0.0) {
            return LatencyTier.UNKNOWN
        }
        return when {
            latencyMs < LOW_LATENCY_MS -> LatencyTier.LOW
            latencyMs < MEDIUM_LATENCY_MS -> LatencyTier.MEDIUM
            else -> LatencyTier.HIGH
        }
    }

    private fun maxTier(a: LatencyTier, b: LatencyTier): LatencyTier {
        val order =
            mapOf(
                LatencyTier.UNKNOWN to 0,
                LatencyTier.LOW to 1,
                LatencyTier.MEDIUM to 2,
                LatencyTier.HIGH to 3,
            )
        return if ((order[a] ?: 0) >= (order[b] ?: 0)) a else b
    }

    private fun recommendedBackend(output: StreamCapabilitySide): String =
        when {
            output.performanceModeGranted && output.actualAudioApi == "AAudio" -> "AAudio"
            output.performanceModeGranted -> output.actualAudioApi
            output.actualAudioApi.isNotBlank() -> output.actualAudioApi
            else -> "Unspecified"
        }
}
