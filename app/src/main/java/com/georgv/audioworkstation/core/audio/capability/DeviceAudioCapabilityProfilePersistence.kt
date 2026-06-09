package com.georgv.audioworkstation.core.audio.capability

interface DeviceAudioCapabilityProfilePersistence {
    suspend fun save(profile: DeviceAudioCapabilityProfile)

    suspend fun load(profileId: String): DeviceAudioCapabilityProfile?

    suspend fun clear(profileId: String)

    suspend fun listProfileIds(): List<String>
}
