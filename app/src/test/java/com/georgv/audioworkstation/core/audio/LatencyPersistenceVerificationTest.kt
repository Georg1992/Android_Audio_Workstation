package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfileCache
import com.georgv.audioworkstation.core.audio.capability.InMemoryDeviceAudioCapabilityProfileStore
import com.georgv.audioworkstation.core.audio.capability.resolveBlocking
import com.georgv.audioworkstation.core.audio.capability.sampleCapabilityProfile
import com.georgv.audioworkstation.core.audio.capability.testAudioCapabilityProfileResolver
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class LatencyPersistenceVerificationTest {
    private val speakerRoute = "builtin_speaker_sr_48000"
    private val wiredRoute = "wired_headphones_sr_48000"

    @Before
    fun clearCaches() {
        DeviceAudioCapabilityProfileCache.clearForTests()
    }

    @Test
    fun `empty store resolves without measured round trip`() {
        val storage = InMemoryDeviceAudioCapabilityProfileStore()
        val resolver = testAudioCapabilityProfileResolver(storage, speakerRoute)
        DeviceAudioCapabilityProfileCache.clearForTests()
        val capability = resolver.resolveBlocking(48_000)
        assertNull(capability.roundTripMs)
    }

    @Test
    fun `stored calibrated profile resolves with measured round trip`() {
        val storage = InMemoryDeviceAudioCapabilityProfileStore()
        runBlocking {
            storage.save(sampleCapabilityProfile(routeKey = speakerRoute, sampleRate = 48_000, roundTripMs = 103.0))
        }
        val resolver = testAudioCapabilityProfileResolver(storage, speakerRoute)
        DeviceAudioCapabilityProfileCache.clearForTests()
        val capability = resolver.resolveBlocking(48_000)
        assertEquals(103.0, capability.roundTripMs)
    }

    @Test
    fun `route mismatch returns no round trip even when another route is stored`() {
        val storage = InMemoryDeviceAudioCapabilityProfileStore()
        runBlocking {
            storage.save(sampleCapabilityProfile(routeKey = speakerRoute, sampleRate = 48_000, roundTripMs = 211.0))
        }
        DeviceAudioCapabilityProfileCache.clearForTests()
        val wiredResolver = testAudioCapabilityProfileResolver(storage, wiredRoute)
        val capability = wiredResolver.resolveBlocking(48_000)
        assertNull(capability.roundTripMs)
    }
}
