package com.georgv.audioworkstation.core.audio.capability

data class DeviceAudioIdentity(
    val deviceManufacturer: String,
    val deviceModel: String,
    val androidVersion: String,
    val sdkInt: Int,
    val routeKey: String,
    val routeType: AudioRouteType,
    val sampleRate: Int,
)
