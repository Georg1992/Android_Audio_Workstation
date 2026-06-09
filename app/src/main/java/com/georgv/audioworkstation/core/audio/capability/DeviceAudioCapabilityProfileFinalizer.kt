package com.georgv.audioworkstation.core.audio.capability

object DeviceAudioCapabilityProfileFinalizer {
    fun finalize(
        profile: DeviceAudioCapabilityProfile,
        routeUnchanged: Boolean = true,
    ): DeviceAudioCapabilityProfile {
        val migratedHistory =
            CapabilityMeasurementHistoryMerger.migrateFromLegacyRoundTrips(
                history = profile.measurementHistory,
                recentRoundTripMs = profile.recentRoundTripMs,
                updatedAt = profile.updatedAt,
            )
        val withHistory =
            profile.copy(
                measurementHistory = migratedHistory,
                recentRoundTripMs =
                    if (profile.recentRoundTripMs.isNotEmpty()) {
                        profile.recentRoundTripMs
                    } else {
                        migratedHistory.roundTripMs.map { it.valueMs }
                    },
            )
        val validation = CapabilityProfileValidator.validate(withHistory)
        val validated =
            withHistory.copy(
                calibration = validation.calibration,
                validation = validation.flags,
            )
        val classified = DeviceAudioCapabilityClassifier.classify(validated)
        val state =
            CapabilityProfileStateEvaluator.evaluate(
                profile = validated,
                validation = validation,
                routeUnchanged = routeUnchanged,
            )
        return validated.copy(
            derived =
                classified.copy(
                    profileState = state,
                ),
        )
    }
}
