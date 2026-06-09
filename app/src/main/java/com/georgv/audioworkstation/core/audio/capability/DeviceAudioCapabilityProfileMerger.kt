package com.georgv.audioworkstation.core.audio.capability

object DeviceAudioCapabilityProfileMerger {
    fun merge(
        existing: DeviceAudioCapabilityProfile?,
        incoming: DeviceAudioCapabilityProfile,
        routeUnchanged: Boolean = true,
    ): DeviceAudioCapabilityProfile {
        if (existing == null) {
            val history =
                CapabilityMeasurementHistoryMerger.appendFromProfile(
                    existing = CapabilityMeasurementHistory(),
                    incoming = incoming,
                )
            return DeviceAudioCapabilityProfileFinalizer.finalize(
                incoming.copy(
                    measurementHistory = history,
                    recentRoundTripMs = history.roundTripMs.map { it.valueMs },
                ),
                routeUnchanged,
            )
        }
        if (existing.profileId != incoming.profileId &&
            (existing.routeKey != incoming.routeKey || existing.sampleRate != incoming.sampleRate)
        ) {
            error(
                "profile identity mismatch: ${existing.profileId} vs ${incoming.profileId}",
            )
        }

        val mergedCalibration = mergeCalibration(existing.calibration, incoming.calibration)
        val calibrationAccepted =
            shouldAcceptIncomingCalibration(existing.calibration, incoming.calibration)
        return finalizeMergedProfile(existing, incoming, mergedCalibration, calibrationAccepted, routeUnchanged)
    }

    private fun finalizeMergedProfile(
        existing: DeviceAudioCapabilityProfile,
        incoming: DeviceAudioCapabilityProfile,
        mergedCalibration: MeasuredCalibrationData,
        calibrationAccepted: Boolean,
        routeUnchanged: Boolean,
    ): DeviceAudioCapabilityProfile {
        val mergedOutput = mergeStreamSide(existing.output, incoming.output)
        val mergedInput = mergeStreamSide(existing.input, incoming.input)
        val mergedStartup = mergeStartup(existing.startup, incoming.startup)
        val mergedMeasurements = mergeMeasurements(existing.recentRoundTripMs, incoming)
        val mergedCalibrationWithJitter =
            mergedCalibration.copy(
                measuredJitterMs =
                    CaptureDelayEstimator.jitterFromMeasurements(mergedMeasurements)
                        ?: mergedCalibration.measuredJitterMs,
            )

        val mergedBase =
            existing.copy(
                profileId = incoming.profileId,
                deviceManufacturer = incoming.deviceManufacturer,
                deviceModel = incoming.deviceModel,
                androidVersion = incoming.androidVersion,
                sdkInt = incoming.sdkInt,
                routeType = incoming.routeType,
                output = mergedOutput,
                input = mergedInput,
                calibration = mergedCalibrationWithJitter,
                startup = mergedStartup,
                recentRoundTripMs = mergedMeasurements,
                updatedAt = incoming.updatedAt,
            )
        val migratedHistory =
            CapabilityMeasurementHistoryMerger.migrateFromLegacyRoundTrips(
                history = mergedBase.measurementHistory,
                recentRoundTripMs = mergedMeasurements,
                updatedAt = incoming.updatedAt,
            )
        val mergedHistory =
            if (calibrationAccepted && incoming.calibration.measuredRoundTripMs != null) {
                CapabilityMeasurementHistoryMerger.appendFromProfile(
                    existing = migratedHistory,
                    incoming = mergedBase,
                )
            } else {
                CapabilityMeasurementHistoryMerger.appendBackendsOnly(
                    existing = migratedHistory,
                    profile = mergedBase,
                )
            }
        val merged =
            mergedBase.copy(
                measurementHistory = mergedHistory,
                recentRoundTripMs = mergedHistory.roundTripMs.map { it.valueMs },
            )
        return DeviceAudioCapabilityProfileFinalizer.finalize(merged, routeUnchanged)
    }

    fun shouldAcceptIncomingCalibration(
        existing: MeasuredCalibrationData,
        incoming: MeasuredCalibrationData,
    ): Boolean {
        if (incoming.calibratedAt <= 0L) {
            return false
        }
        if (existing.calibratedAt <= 0L) {
            return incoming.calibrationConfidence > 0.0
        }
        if (incoming.calibrationConfidence > existing.calibrationConfidence) {
            return true
        }
        if (incoming.calibrationConfidence < existing.calibrationConfidence) {
            return false
        }
        return incoming.calibratedAt >= existing.calibratedAt
    }

    private fun mergeCalibration(
        existing: MeasuredCalibrationData,
        incoming: MeasuredCalibrationData,
    ): MeasuredCalibrationData {
        if (!shouldAcceptIncomingCalibration(existing, incoming)) {
            return existing
        }
        if (incoming.calibratedAt <= 0L && incoming.measuredRoundTripMs == null) {
            return existing
        }
        return incoming
    }

    private fun mergeStreamSide(
        existing: StreamCapabilitySide,
        incoming: StreamCapabilitySide,
    ): StreamCapabilitySide {
        if (incoming.latencyConfidence >= existing.latencyConfidence) {
            return incoming
        }
        return existing
    }

    private fun mergeStartup(
        existing: StartupMetricsData,
        incoming: StartupMetricsData,
    ): StartupMetricsData {
        if (incoming.startupMetricsUpdatedAt <= existing.startupMetricsUpdatedAt) {
            return existing
        }
        return incoming
    }

    private fun mergeMeasurements(
        existing: List<Double>,
        incoming: DeviceAudioCapabilityProfile,
    ): List<Double> {
        val appended =
            buildList {
                addAll(existing)
                incoming.calibration.measuredRoundTripMs?.let { add(it) }
            }
        return appended
            .takeLast(DeviceAudioCapabilityProfileBuilder.MAX_RECENT_MEASUREMENTS)
            .distinct()
    }
}
