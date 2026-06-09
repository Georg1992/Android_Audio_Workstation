package com.georgv.audioworkstation.core.audio.latency

import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.georgv.audioworkstation.core.audio.AudioRouteKeyProvider

data class AudioRouteDeviceInfo(
    val routeKey: String,
    val routeType: String,
    val deviceId: Int,
    val deviceProductName: String?,
) {
    companion object {
        fun current(sampleRate: Int, audioManager: AudioManager?): AudioRouteDeviceInfo {
            val routeKey = AudioRouteKeyProvider.routeKey(sampleRate, audioManager)
            if (audioManager == null) {
                return AudioRouteDeviceInfo(
                    routeKey = routeKey,
                    routeType = "unknown",
                    deviceId = -1,
                    deviceProductName = null,
                )
            }
            @Suppress("DEPRECATION")
            val device =
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull()
            return if (device == null) {
                AudioRouteDeviceInfo(
                    routeKey = routeKey,
                    routeType = "unknown",
                    deviceId = -1,
                    deviceProductName = null,
                )
            } else {
                AudioRouteDeviceInfo(
                    routeKey = routeKey,
                    routeType = routeTypeLabel(device.type),
                    deviceId = device.id,
                    deviceProductName = device.productName?.toString(),
                )
            }
        }

        private fun routeTypeLabel(type: Int): String =
            when (type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
                else -> "device_type_$type"
            }
    }
}
