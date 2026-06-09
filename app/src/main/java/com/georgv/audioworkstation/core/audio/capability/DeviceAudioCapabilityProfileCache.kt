package com.georgv.audioworkstation.core.audio.capability

import java.util.concurrent.atomic.AtomicReference

object DeviceAudioCapabilityProfileCache {
    private val activeProfileId = AtomicReference<String?>(null)
    private val activeProfile = AtomicReference<DeviceAudioCapabilityProfile?>(null)

    fun remember(profile: DeviceAudioCapabilityProfile) {
        activeProfileId.set(profile.profileId)
        activeProfile.set(profile)
    }

    fun current(): DeviceAudioCapabilityProfile? = activeProfile.get()

    fun currentProfileId(): String? = activeProfileId.get()

    fun invalidate() {
        activeProfileId.set(null)
        activeProfile.set(null)
    }

    internal fun clearForTests() {
        invalidate()
    }
}
