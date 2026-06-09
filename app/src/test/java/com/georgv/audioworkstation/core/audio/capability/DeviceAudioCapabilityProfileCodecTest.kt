package com.georgv.audioworkstation.core.audio.capability

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceAudioCapabilityProfileCodecTest {
    @Test
    fun roundTrip_preservesProfile() {
        val profile = sampleCapabilityProfile()
        val encoded = DeviceAudioCapabilityProfileCodec.encode(profile)
        val decoded =
            DeviceAudioCapabilityProfileCodec.decode(encoded)
                ?: error("decode failed for json=$encoded")
        assertEquals(profile, decoded)
    }
}
