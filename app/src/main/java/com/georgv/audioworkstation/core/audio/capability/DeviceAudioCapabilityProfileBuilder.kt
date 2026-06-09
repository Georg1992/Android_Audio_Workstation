package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.latency.OboeCapabilityLabels
import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe
import com.georgv.audioworkstation.engine.PlaybackSessionTimings

object DeviceAudioCapabilityProfileBuilder {
    const val MAX_RECENT_MEASUREMENTS = 10

    private const val StreamLatencyConfidenceHalAndStable = 0.9
    private const val StreamLatencyConfidenceHalAndTimestamp = 0.75
    private const val StreamLatencyConfidenceHalOnly = 0.7
    private const val StreamLatencyConfidenceDefault = 0.4

    fun streamSideFromProbe(
        probe: OboeStreamCapabilityProbe,
        routeHighLatency: Boolean,
    ): StreamCapabilitySide {
        val sampleRate = probe.sampleRateHz
        val bufferMs = framesToMs(probe.bufferSizeInFrames, sampleRate)
        val burstMs = framesToMs(probe.framesPerBurst, sampleRate)
        val performanceGranted = OboeCapabilityLabels.lowLatencyApplied(probe)
        val sharingGranted = OboeCapabilityLabels.exclusiveApplied(probe)
        val halMs = probe.estimatedStreamLatencyMs
        return StreamCapabilitySide(
            requestedAudioApi = "Unspecified",
            actualAudioApi = OboeCapabilityLabels.audioApiLabel(probe.audioApi),
            requestedPerformanceMode = OboeCapabilityLabels.PERFORMANCE_MODE_REQUESTED,
            actualPerformanceMode = OboeCapabilityLabels.performanceModeLabel(probe.performanceModeActual),
            performanceModeGranted = performanceGranted,
            requestedSharingMode = OboeCapabilityLabels.SHARING_MODE_REQUESTED,
            actualSharingMode = OboeCapabilityLabels.sharingModeLabel(probe.sharingModeActual),
            sharingModeGranted = sharingGranted,
            framesPerBurst = probe.framesPerBurst,
            bufferSizeFrames = probe.bufferSizeInFrames,
            bufferCapacityFrames = probe.bufferCapacityInFrames,
            bufferSizeMs = bufferMs,
            burstMs = burstMs,
            halReportedLatencyMs = halMs,
            latencyConfidence = streamLatencyConfidence(probe, halMs, isInput = false),
            lowLatencyPathDenied =
                OboeCapabilityLabels.PERFORMANCE_MODE_REQUESTED == "LowLatency" && !performanceGranted,
            timestampAvailable = probe.timestampAvailable,
            timestampStable = probe.timestampStable,
            highLatencyRoute = routeHighLatency,
        )
    }

    fun inputSideFromProbe(
        probe: OboeStreamCapabilityProbe,
        routeHighLatency: Boolean,
    ): StreamCapabilitySide {
        val side = streamSideFromProbe(probe, routeHighLatency)
        return side.copy(
            latencyConfidence =
                streamLatencyConfidence(
                    halMs = probe.estimatedStreamLatencyMs,
                    timestampAvailable = probe.timestampAvailable,
                    timestampStable = probe.timestampStable,
                    isInput = true,
                ),
            highLatencyRoute = routeHighLatency,
        )
    }

    fun calibrationFromMeasurement(
        measuredRoundTripMs: Double?,
        jitterMs: Double?,
        estimatedOutputLatencyMs: Double?,
        calibrationConfidence: Double,
        calibratedAt: Long = System.currentTimeMillis(),
    ): MeasuredCalibrationData {
        val trueCapture =
            CaptureDelayEstimator.estimateTrueCaptureDelayMs(
                measuredRoundTripMs,
                estimatedOutputLatencyMs,
            )
        return MeasuredCalibrationData(
            measuredRoundTripMs = measuredRoundTripMs,
            measuredJitterMs = jitterMs,
            estimatedOutputLatencyMs = estimatedOutputLatencyMs,
            estimatedTrueCaptureDelayMs = trueCapture,
            calibrationConfidence = calibrationConfidence,
            calibratedAt = calibratedAt,
        )
    }

    fun startupFromTimings(timings: PlaybackSessionTimings?): StartupMetricsData {
        if (timings == null) {
            return StartupMetricsData()
        }
        return StartupMetricsData(
            armToFirstInputMs = timings.armToFirstInputMs(),
            armToFirstAudibleMs = timings.armToFirstAudibleMs(),
            firstInputToFirstAudibleMs = timings.firstInputToFirstAudibleMs(),
            startupMetricsUpdatedAt = System.currentTimeMillis(),
        )
    }

    fun skeleton(
        identity: DeviceAudioIdentity,
        output: StreamCapabilitySide,
        input: StreamCapabilitySide,
        now: Long = System.currentTimeMillis(),
    ): DeviceAudioCapabilityProfile {
        val profileId =
            DeviceAudioCapabilityProfileId.compute(
                routeKey = identity.routeKey,
                outputActualAudioApi = output.actualAudioApi,
                inputActualAudioApi = input.actualAudioApi,
            )
        val profile =
            DeviceAudioCapabilityProfile(
                profileId = profileId,
                deviceManufacturer = identity.deviceManufacturer,
                deviceModel = identity.deviceModel,
                androidVersion = identity.androidVersion,
                sdkInt = identity.sdkInt,
                routeKey = identity.routeKey,
                routeType = identity.routeType,
                sampleRate = identity.sampleRate,
                output = output,
                input = input,
                calibration = MeasuredCalibrationData(),
                startup = StartupMetricsData(),
                derived = DerivedCapabilityData(),
                createdAt = now,
                updatedAt = now,
            )
        return DeviceAudioCapabilityProfileFinalizer.finalize(profile)
    }

    private fun streamLatencyConfidence(
        probe: OboeStreamCapabilityProbe,
        halMs: Double?,
        isInput: Boolean,
    ): Double =
        streamLatencyConfidence(
            halMs = halMs,
            timestampAvailable = probe.timestampAvailable,
            timestampStable = probe.timestampStable,
            isInput = isInput,
        )

    private fun streamLatencyConfidence(
        halMs: Double?,
        timestampAvailable: Boolean,
        timestampStable: Boolean,
        isInput: Boolean,
    ): Double {
        val halValid = halMs != null && halMs.isFinite() && halMs >= 0.0
        return when {
            halValid && timestampStable -> StreamLatencyConfidenceHalAndStable
            halValid && timestampAvailable -> StreamLatencyConfidenceHalAndTimestamp
            halValid -> StreamLatencyConfidenceHalOnly
            isInput && !timestampAvailable -> 0.0
            else -> StreamLatencyConfidenceDefault
        }
    }

    private fun framesToMs(frames: Int, sampleRateHz: Int): Double {
        if (sampleRateHz <= 0 || frames <= 0) {
            return 0.0
        }
        return frames * 1000.0 / sampleRateHz.toDouble()
    }
}
