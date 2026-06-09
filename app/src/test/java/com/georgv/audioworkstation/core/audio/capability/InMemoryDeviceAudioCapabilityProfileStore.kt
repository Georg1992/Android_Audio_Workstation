package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.AudioRouteKeySource
import kotlinx.coroutines.runBlocking

class InMemoryDeviceAudioCapabilityProfileStore : DeviceAudioCapabilityProfilePersistence {
    private val profiles = linkedMapOf<String, DeviceAudioCapabilityProfile>()

    override suspend fun save(profile: DeviceAudioCapabilityProfile) {
        profiles[profile.profileId] = profile
    }

    override suspend fun load(profileId: String): DeviceAudioCapabilityProfile? = profiles[profileId]

    override suspend fun clear(profileId: String) {
        profiles.remove(profileId)
    }

    override suspend fun listProfileIds(): List<String> = profiles.keys.toList()

    fun clearAll() {
        profiles.clear()
    }
}

fun sampleCapabilityProfile(
    routeKey: String = "builtin_speaker_sr_44100",
    sampleRate: Int = 44_100,
    outputApi: String = "AAudio",
    inputApi: String = "AAudio",
    halOutputMs: Double = 12.0,
    roundTripMs: Double = 95.0,
    captureDelayMs: Double = 83.0,
    calibrationConfidence: Double = 0.85,
): DeviceAudioCapabilityProfile {
    val output =
        StreamCapabilitySide(
            actualAudioApi = outputApi,
            actualPerformanceMode = "LowLatency",
            performanceModeGranted = true,
            halReportedLatencyMs = halOutputMs,
            latencyConfidence = 0.9,
            timestampAvailable = true,
            timestampStable = true,
            framesPerBurst = 256,
            bufferSizeFrames = 512,
            bufferCapacityFrames = 2048,
            bufferSizeMs = 11.6,
            burstMs = 5.8,
        )
    val input =
        StreamCapabilitySide(
            actualAudioApi = inputApi,
            actualPerformanceMode = "LowLatency",
            performanceModeGranted = true,
            halReportedLatencyMs = 20.0,
            latencyConfidence = 0.75,
            timestampAvailable = true,
            framesPerBurst = 192,
            bufferSizeFrames = 384,
            bufferCapacityFrames = 1536,
            bufferSizeMs = 8.7,
            burstMs = 4.35,
        )
    val identity =
        DeviceAudioIdentity(
            deviceManufacturer = "Test",
            deviceModel = "Device",
            androidVersion = "14",
            sdkInt = 34,
            routeKey = routeKey,
            routeType = AudioRouteType.SPEAKER,
            sampleRate = sampleRate,
        )
    val profile =
        DeviceAudioCapabilityProfileBuilder.skeleton(identity, output, input).copy(
            calibration =
                MeasuredCalibrationData(
                    measuredRoundTripMs = roundTripMs,
                    measuredJitterMs = 4.0,
                    estimatedOutputLatencyMs = halOutputMs,
                    estimatedTrueCaptureDelayMs = captureDelayMs,
                    calibrationConfidence = calibrationConfidence,
                    calibratedAt = 1_700_000_000_000L,
                ),
            recentRoundTripMs = listOf(roundTripMs),
            createdAt = 1_700_000_000_000L,
            updatedAt = 1_700_000_000_000L,
        )
    return DeviceAudioCapabilityProfileFinalizer.finalize(profile)
}

fun testAudioCapabilityProfileResolver(
    storage: InMemoryDeviceAudioCapabilityProfileStore = InMemoryDeviceAudioCapabilityProfileStore(),
    routeKey: String = "unknown_route_sr_48000",
): AudioCapabilityProfileResolver =
    AudioCapabilityProfileResolver(
        store = storage,
        routeKeySource = AudioRouteKeySource { routeKey },
        identityProvider =
            DeviceAudioIdentitySource { requestedSampleRate ->
                DeviceAudioIdentity(
                    deviceManufacturer = "Test",
                    deviceModel = "Device",
                    androidVersion = "14",
                    sdkInt = 34,
                    routeKey = routeKey,
                    routeType = AudioRouteType.UNKNOWN,
                    sampleRate = requestedSampleRate,
                )
            },
    )

fun calibratedResolvedCapability(
    routeKey: String = "wired_headphones_sr_48000",
    sampleRate: Int = 48_000,
    roundTripMs: Double = 103.0,
    resolver: AudioCapabilityProfileResolver = testAudioCapabilityProfileResolver(routeKey = routeKey),
): ResolvedAudioCapability =
    resolver.toResolvedCapability(
        sampleCapabilityProfile(
            routeKey = routeKey,
            sampleRate = sampleRate,
            roundTripMs = roundTripMs,
        ),
    )

fun AudioCapabilityProfileResolver.resolveBlocking(sampleRate: Int): ResolvedAudioCapability =
    runBlocking { resolve(sampleRate) }
