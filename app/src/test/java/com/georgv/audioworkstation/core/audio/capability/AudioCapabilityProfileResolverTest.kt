package com.georgv.audioworkstation.core.audio.capability

import com.georgv.audioworkstation.core.audio.AudioRouteKeySource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AudioCapabilityProfileResolverTest {
    private val store = InMemoryDeviceAudioCapabilityProfileStore()
    private lateinit var resolver: AudioCapabilityProfileResolver

    @Before
    fun setUp() {
        DeviceAudioCapabilityProfileCache.invalidate()
        store.clearAll()
        resolver =
            AudioCapabilityProfileResolver(
                store = store,
                routeKeySource = AudioRouteKeySource { "builtin_speaker_sr_44100" },
                identityProvider =
                    DeviceAudioIdentitySource { sampleRate ->
                        DeviceAudioIdentity(
                            deviceManufacturer = "Test",
                            deviceModel = "Device",
                            androidVersion = "14",
                            sdkInt = 34,
                            routeKey = "builtin_speaker_sr_44100",
                            routeType = AudioRouteType.SPEAKER,
                            sampleRate = sampleRate,
                        )
                    },
            )
    }

    @Test
    fun resolve_loadsPersistedProfile() =
        runBlocking {
            val profile = sampleCapabilityProfile()
            store.save(profile)

            val resolved = resolver.resolve(44_100)

            assertNotNull(resolved.profile)
            assertEquals(12.0, resolved.outputLatencyMs!!, 0.001)
            assertEquals(83.0, resolved.inputCaptureDelayMs!!, 0.001)
            assertEquals(95.0, resolved.roundTripMs!!, 0.001)
            assertTrue(resolved.lowLatencyOutputPathGranted)
        }

    @Test
    fun resolve_missingProfileHasNoLatencyMeasurements() =
        runBlocking {
            val resolved = resolver.resolve(44_100)

            assertNull(resolved.profile)
            assertNull(resolved.roundTripMs)
        }
}
