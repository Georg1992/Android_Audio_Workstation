package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.engine.AudioCallbackCostSnapshot
import com.georgv.audioworkstation.engine.AudioInputLoopCostSnapshot

object CapabilityMeasurementHistoryMerger {
    fun appendFromProfile(
        existing: CapabilityMeasurementHistory,
        incoming: DeviceAudioCapabilityProfile,
        appOutputCost: AudioCallbackCostSnapshot? = null,
        appInputCost: AudioInputLoopCostSnapshot? = null,
    ): CapabilityMeasurementHistory {
        val now = incoming.updatedAt
        val outputMs = DeviceAudioCapabilityClassifier.effectiveOutputLatencyMs(incoming)
        val calibration = incoming.calibration

        return existing.copy(
            outputFloorMs =
                appendSample(
                    existing.outputFloorMs,
                    outputMs,
                    now,
                ),
            roundTripMs =
                appendSample(
                    existing.roundTripMs,
                    calibration.measuredRoundTripMs,
                    calibration.calibratedAt.takeIf { it > 0L } ?: now,
                ),
            captureDelayMs =
                appendSample(
                    existing.captureDelayMs,
                    calibration.estimatedTrueCaptureDelayMs,
                    calibration.calibratedAt.takeIf { it > 0L } ?: now,
                ),
            jitterMs =
                appendSample(
                    existing.jitterMs,
                    calibration.measuredJitterMs,
                    calibration.calibratedAt.takeIf { it > 0L } ?: now,
                ),
            appOutputCostP95Us =
                appendSample(
                    existing.appOutputCostP95Us,
                    appOutputCost?.callbackP95Us?.toDouble(),
                    now,
                ),
            appInputProcessingP95Us =
                appendSample(
                    existing.appInputProcessingP95Us,
                    appInputCost?.processingP95Us?.toDouble(),
                    now,
                ),
            backends = mergeBackends(existing.backends, incoming),
        )
    }

    fun appendBackendsOnly(
        existing: CapabilityMeasurementHistory,
        profile: DeviceAudioCapabilityProfile,
    ): CapabilityMeasurementHistory =
        existing.copy(backends = mergeBackends(existing.backends, profile))

    fun migrateFromLegacyRoundTrips(
        history: CapabilityMeasurementHistory,
        recentRoundTripMs: List<Double>,
        updatedAt: Long,
    ): CapabilityMeasurementHistory {
        if (history.roundTripMs.isNotEmpty() || recentRoundTripMs.isEmpty()) {
            return history
        }
        return history.copy(
            roundTripMs =
                recentRoundTripMs.map { value ->
                    LatencyMeasurementSample(valueMs = value, measuredAt = updatedAt)
                },
        )
    }

    private fun appendSample(
        existing: List<LatencyMeasurementSample>,
        value: Double?,
        measuredAt: Long,
    ): List<LatencyMeasurementSample> {
        if (value == null || !value.isFinite() || value < 0.0) {
            return existing
        }
        return (existing + LatencyMeasurementSample(valueMs = value, measuredAt = measuredAt))
            .takeLast(DeviceAudioCapabilityProfileBuilder.MAX_RECENT_MEASUREMENTS)
    }

    private fun mergeBackends(
        existing: List<BackendCapabilitySnapshot>,
        profile: DeviceAudioCapabilityProfile,
    ): List<BackendCapabilitySnapshot> {
        val outputSnapshot = backendFromSide(profile.output, "output", profile.updatedAt)
        val inputSnapshot = backendFromSide(profile.input, "input", profile.updatedAt)
        val appended =
            buildList {
                addAll(existing)
                if (outputSnapshot != null) {
                    add(outputSnapshot)
                }
                if (inputSnapshot != null) {
                    add(inputSnapshot)
                }
            }
        return appended
            .distinctBy { "${it.direction}|${it.audioApi}|${it.performanceMode}|${it.sharingMode}" }
            .takeLast(DeviceAudioCapabilityProfileBuilder.MAX_RECENT_MEASUREMENTS * 2)
    }

    private fun backendFromSide(
        side: StreamCapabilitySide,
        direction: String,
        testedAt: Long,
    ): BackendCapabilitySnapshot? {
        if (side.actualAudioApi == "Unknown" || side.bufferSizeFrames <= 0) {
            return null
        }
        return BackendCapabilitySnapshot(
            audioApi = side.actualAudioApi,
            direction = direction,
            performanceMode = side.actualPerformanceMode,
            performanceModeGranted = side.performanceModeGranted,
            sharingMode = side.actualSharingMode,
            sharingModeGranted = side.sharingModeGranted,
            framesPerBurst = side.framesPerBurst,
            bufferSizeFrames = side.bufferSizeFrames,
            halReportedLatencyMs = side.halReportedLatencyMs,
            latencyAvailable = side.hasValidHalLatency(),
            measuredLatencyMs = side.halReportedLatencyMs ?: side.bufferSizeMs.takeIf { it > 0.0 },
            testedAt = testedAt,
        )
    }
}
