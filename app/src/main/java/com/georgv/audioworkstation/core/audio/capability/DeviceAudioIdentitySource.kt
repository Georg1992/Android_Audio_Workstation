package com.georgv.audioworkstation.core.audio.capability

fun interface DeviceAudioIdentitySource {
    fun currentIdentity(sampleRate: Int): DeviceAudioIdentity
}
