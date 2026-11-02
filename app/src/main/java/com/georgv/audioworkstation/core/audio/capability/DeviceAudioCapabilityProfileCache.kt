package com.georgv.audioworkstation.core.audio.capability

import java.util.concurrent.atomic.AtomicReference

object DeviceAudioCapabilityProfileCache {
    private val activeProfile = AtomicReference<DeviceAudioCapabilityProfile?>(null)

    fun remember(profile: DeviceAudioCapabilityProfile) {
        activeProfile.set(profile)
    }

    fun current(): DeviceAudioCapabilityProfile? = activeProfile.get()

    fun invalidate() {
        activeProfile.set(null)
    }

    internal fun clearForTests() {
        invalidate()
    }
}
