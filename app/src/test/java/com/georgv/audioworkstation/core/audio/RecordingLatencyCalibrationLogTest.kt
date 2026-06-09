package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfileCache
import com.georgv.audioworkstation.core.audio.capability.InMemoryDeviceAudioCapabilityProfileStore
import com.georgv.audioworkstation.core.audio.capability.calibratedResolvedCapability
import com.georgv.audioworkstation.core.audio.capability.resolveBlocking
import com.georgv.audioworkstation.core.audio.capability.sampleCapabilityProfile
import com.georgv.audioworkstation.core.audio.capability.testAudioCapabilityProfileResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecordingLatencyCalibrationLogTest {
    @Before
    fun clearCaches() {
        DeviceAudioCapabilityProfileCache.clearForTests()
    }

    @Test
    fun `uncalibrated route logs raw placement detail`() {
        val capability = testAudioCapabilityProfileResolver().resolveBlocking(48_000)
        assertNull(capability.roundTripMs)
        assertTrue(
            RecordingLatencyCalibrationLog.routeUncalibratedLogMessage()
                .contains(RecordingLatencyCalibrationLog.UNCALIBRATED_DETAIL),
        )
    }

    @Test
    fun `calibrated route logs measured round trip detail`() {
        val capability = calibratedResolvedCapability(roundTripMs = 103.0)
        assertEquals(103.0, capability.roundTripMs)
        assertTrue(
            RecordingLatencyCalibrationLog.routeCalibratedLogMessage()
                .contains(RecordingLatencyCalibrationLog.CALIBRATED_DETAIL),
        )
    }

    @Test
    fun `resolve returns uncalibrated when store is empty`() {
        val resolver = testAudioCapabilityProfileResolver(routeKey = "unknown_route_sr_48000")
        val capability = resolver.resolveBlocking(48_000)
        assertNull(capability.roundTripMs)
    }

    @Test
    fun `resolve returns measured round trip when store has matching route`() {
        val routeKey = "wired_headphones_sr_48000"
        val storage = InMemoryDeviceAudioCapabilityProfileStore()
        val profile = sampleCapabilityProfile(routeKey = routeKey, sampleRate = 48_000, roundTripMs = 103.0)
        kotlinx.coroutines.runBlocking { storage.save(profile) }
        DeviceAudioCapabilityProfileCache.clearForTests()
        val resolver = testAudioCapabilityProfileResolver(storage, routeKey)
        val capability = resolver.resolveBlocking(48_000)
        assertEquals(103.0, capability.roundTripMs)
    }
}
