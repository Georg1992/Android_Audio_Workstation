package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.AudioRouteKeySource
import com.georgv.audioworkstation.engine.OboeStreamCapabilityProbe
import com.georgv.audioworkstation.engine.PlaybackSessionTimings
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AudioCapabilityProfileResolver @Inject constructor(
    private val store: DeviceAudioCapabilityProfilePersistence,
    private val routeKeySource: AudioRouteKeySource,
    private val identityProvider: DeviceAudioIdentitySource,
) {
    private var lastResolvedRouteKey: String? = null

    suspend fun resolve(sampleRate: Int): ResolvedAudioCapability {
        val identity = identityProvider.currentIdentity(sampleRate)
        val routeChanged = lastResolvedRouteKey != null && lastResolvedRouteKey != identity.routeKey
        if (routeChanged) {
            DeviceAudioCapabilityProfileCache.invalidate()
        }
        val routeUnchanged = !routeChanged
        lastResolvedRouteKey = identity.routeKey

        val cached = DeviceAudioCapabilityProfileCache.current()
        if (cached != null && cached.routeKey == identity.routeKey && cached.sampleRate == sampleRate) {
            return toResolved(cached, logLoaded = false, routeUnchanged = routeUnchanged)
        }

        val stored = loadBestStoredProfile(identity.routeKey, sampleRate)
        if (stored != null) {
            val finalized =
                DeviceAudioCapabilityProfileFinalizer.finalize(
                    stored,
                    routeUnchanged = routeUnchanged,
                )
            DeviceAudioCapabilityProfileCache.remember(finalized)
            return toResolved(finalized, logLoaded = true, routeUnchanged = routeUnchanged)
        }

        val profileId = profileIdForIdentity(identity, cached)
        AudioCapabilityProfileLog.logMissing(
            profileId = profileId,
            routeKey = identity.routeKey,
            sampleRate = sampleRate,
        )
        return emptyResolved(identity, profileId, routeUnchanged = routeUnchanged)
    }

    suspend fun listStoredProfiles(): List<DeviceAudioCapabilityProfile> =
        store.listProfileIds()
            .mapNotNull { store.load(it) }
            .sortedByDescending { it.updatedAt }

    fun toResolvedCapability(profile: DeviceAudioCapabilityProfile): ResolvedAudioCapability =
        toResolved(profile, logLoaded = false, routeUnchanged = true)

    suspend fun mergeStreamCapabilities(
        sampleRate: Int,
        outputProbe: OboeStreamCapabilityProbe?,
        inputProbe: OboeStreamCapabilityProbe?,
    ): DeviceAudioCapabilityProfile? {
        val identity = identityProvider.currentIdentity(sampleRate)
        val routeHighLatency = identity.routeType.isHighLatencyRoute()
        val output =
            outputProbe?.let { DeviceAudioCapabilityProfileBuilder.streamSideFromProbe(it, routeHighLatency) }
                ?: StreamCapabilitySide(highLatencyRoute = routeHighLatency)
        val input =
            inputProbe?.let { DeviceAudioCapabilityProfileBuilder.inputSideFromProbe(it, routeHighLatency) }
                ?: StreamCapabilitySide(highLatencyRoute = routeHighLatency)

        val incoming =
            DeviceAudioCapabilityProfileBuilder.skeleton(
                identity = identity,
                output = output,
                input = input,
                now = System.currentTimeMillis(),
            )
        return persistMerged(incoming)
    }

    suspend fun mergeCalibration(
        sampleRate: Int,
        measuredRoundTripMs: Double?,
        jitterMs: Double?,
        estimatedOutputLatencyMs: Double?,
        calibrationConfidence: Double,
        outputProbe: OboeStreamCapabilityProbe? = null,
        inputProbe: OboeStreamCapabilityProbe? = null,
    ): DeviceAudioCapabilityProfile? {
        val identity = identityProvider.currentIdentity(sampleRate)
        val routeHighLatency = identity.routeType.isHighLatencyRoute()
        val output =
            outputProbe?.let { DeviceAudioCapabilityProfileBuilder.streamSideFromProbe(it, routeHighLatency) }
                ?: StreamCapabilitySide(highLatencyRoute = routeHighLatency)
        val input =
            inputProbe?.let { DeviceAudioCapabilityProfileBuilder.inputSideFromProbe(it, routeHighLatency) }
                ?: StreamCapabilitySide(highLatencyRoute = routeHighLatency)

        val calibration =
            DeviceAudioCapabilityProfileBuilder.calibrationFromMeasurement(
                measuredRoundTripMs = measuredRoundTripMs,
                jitterMs = jitterMs,
                estimatedOutputLatencyMs = estimatedOutputLatencyMs,
                calibrationConfidence = calibrationConfidence,
            )

        val existing = loadExistingForIdentity(identity, output, input)
        val incoming =
            (existing ?: DeviceAudioCapabilityProfileBuilder.skeleton(identity, output, input))
                .copy(
                    calibration = calibration,
                    updatedAt = System.currentTimeMillis(),
                )
        return persistMerged(incoming)
    }

    suspend fun mergeStartupMetrics(
        sampleRate: Int,
        timings: PlaybackSessionTimings?,
    ): DeviceAudioCapabilityProfile? {
        val identity = identityProvider.currentIdentity(sampleRate)
        val existing = loadExistingForIdentity(identity, null, null) ?: return null
        val incoming =
            existing.copy(
                startup = DeviceAudioCapabilityProfileBuilder.startupFromTimings(timings),
                updatedAt = System.currentTimeMillis(),
            )
        return persistMerged(incoming)
    }

    private suspend fun persistMerged(incoming: DeviceAudioCapabilityProfile): DeviceAudioCapabilityProfile {
        val existing =
            store.load(incoming.profileId)
                ?: loadBestStoredProfile(incoming.routeKey, incoming.sampleRate)
        val routeUnchanged =
            lastResolvedRouteKey == null || lastResolvedRouteKey == incoming.routeKey
        val merged = DeviceAudioCapabilityProfileMerger.merge(existing, incoming, routeUnchanged)
        if (existing != null && existing.profileId != incoming.profileId) {
            store.clear(existing.profileId)
        }
        store.save(merged)
        DeviceAudioCapabilityProfileCache.remember(merged)
        AudioCapabilityProfileLog.logUpdated(merged)
        val resolved = toResolved(merged, logLoaded = false, routeUnchanged = routeUnchanged)
        AudioCapabilityProfileLog.logLatencySnapshot(resolved)
        LatencyDeviceSummaryLog.log(DeviceLatencySummaryBuilder.build(resolved))
        return merged
    }

    private suspend fun loadExistingForIdentity(
        identity: DeviceAudioIdentity,
        output: StreamCapabilitySide?,
        input: StreamCapabilitySide?,
    ): DeviceAudioCapabilityProfile? {
        val cached = DeviceAudioCapabilityProfileCache.current()
        if (cached != null && cached.routeKey == identity.routeKey) {
            return cached
        }
        val outputApi = output?.actualAudioApi ?: cached?.output?.actualAudioApi ?: "Unknown"
        val inputApi = input?.actualAudioApi ?: cached?.input?.actualAudioApi ?: "Unknown"
        val profileId =
            DeviceAudioCapabilityProfileId.compute(
                routeKey = identity.routeKey,
                outputActualAudioApi = outputApi,
                inputActualAudioApi = inputApi,
            )
        return store.load(profileId) ?: loadBestStoredProfile(identity.routeKey, identity.sampleRate)
    }

    private suspend fun loadBestStoredProfile(
        routeKey: String,
        sampleRate: Int,
    ): DeviceAudioCapabilityProfile? =
        store.listProfileIds()
            .mapNotNull { store.load(it) }
            .filter { profile ->
                profile.routeKey == routeKey && profile.sampleRate == sampleRate
            }
            .maxByOrNull { it.updatedAt }

    private fun profileIdForIdentity(
        identity: DeviceAudioIdentity,
        cached: DeviceAudioCapabilityProfile?,
    ): String {
        if (cached != null && cached.routeKey == identity.routeKey) {
            return cached.profileId
        }
        return DeviceAudioCapabilityProfileId.compute(
            routeKey = identity.routeKey,
            outputActualAudioApi = cached?.output?.actualAudioApi ?: "Unknown",
            inputActualAudioApi = cached?.input?.actualAudioApi ?: "Unknown",
        )
    }

    private fun toResolved(
        profile: DeviceAudioCapabilityProfile,
        logLoaded: Boolean,
        routeUnchanged: Boolean,
    ): ResolvedAudioCapability {
        if (logLoaded) {
            AudioCapabilityProfileLog.logLoaded(profile)
        }
        val finalized =
            if (routeUnchanged) {
                profile
            } else {
                DeviceAudioCapabilityProfileFinalizer.finalize(profile, routeUnchanged = false)
            }
        val completeness =
            com.georgv.audioworkstation.core.audio.capability.audit.CapabilityCompletenessEvaluator
                .evaluate(finalized)
        val validation = CapabilityProfileValidator.validate(finalized)
        val warnings = buildList {
            if (!finalized.output.performanceModeGranted) {
                add("Low latency denied")
            }
            if (finalized.routeType == AudioRouteType.BLUETOOTH) {
                add("Bluetooth route")
            }
            if (finalized.highLatencyOutputRoute) {
                add("output latency high")
            }
            if (!completeness.trueCaptureDelayCaptured) {
                add("capture delay unknown")
            }
            if (validation.flags.measurementInconsistent) {
                add("measurement inconsistent")
            }
        }
        val dataComplete =
            completeness.missingFields.isEmpty() ||
                (completeness.missingFields.size == 1 && completeness.missingFields.contains("startup_metrics"))
        return ResolvedAudioCapability(
            profile = finalized,
            profileId = finalized.profileId,
            routeKey = finalized.routeKey,
            sampleRate = finalized.sampleRate,
            outputLatencyMs = DeviceAudioCapabilityClassifier.effectiveOutputLatencyMs(finalized),
            inputCaptureDelayMs = finalized.calibration.estimatedTrueCaptureDelayMs,
            inputHalLatencyMs = finalized.input.halReportedLatencyMs,
            roundTripMs = finalized.calibration.measuredRoundTripMs,
            jitterMs = finalized.calibration.measuredJitterMs,
            confidence = finalized.overallConfidence(),
            lowLatencyOutputPathGranted = finalized.output.performanceModeGranted,
            lowLatencyInputPathGranted = finalized.input.performanceModeGranted,
            profileState = finalized.derived.profileState,
            routeUnchanged = routeUnchanged,
            validation = validation.flags,
            warnings = warnings,
            dataComplete = dataComplete,
        )
    }

    private fun emptyResolved(
        identity: DeviceAudioIdentity,
        profileId: String,
        routeUnchanged: Boolean,
    ): ResolvedAudioCapability =
        ResolvedAudioCapability(
            profile = null,
            profileId = profileId,
            routeKey = identity.routeKey,
            sampleRate = identity.sampleRate,
            outputLatencyMs = null,
            inputCaptureDelayMs = null,
            inputHalLatencyMs = null,
            roundTripMs = null,
            jitterMs = null,
            confidence = 0.0,
            lowLatencyOutputPathGranted = false,
            lowLatencyInputPathGranted = false,
            profileState = CapabilityProfileState.EMPTY,
            routeUnchanged = routeUnchanged,
            validation = CapabilityValidationFlags(),
            warnings = emptyList(),
            dataComplete = false,
        )
}
