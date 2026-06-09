package com.georgv.audioworkstation.core.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Stable route identifier for latency profiles.
 *
 * Uses Android route metadata when an [AudioManager] is supplied; otherwise
 * falls back to sample-rate-only key.
 */
object AudioRouteKeyProvider {
    fun routeKey(sampleRate: Int): String = fallbackRouteKey(sampleRate)

    fun routeKey(sampleRate: Int, audioManager: AudioManager?): String {
        if (audioManager == null) return fallbackRouteKey(sampleRate)
        val deviceLabel =
            runCatching {
                @Suppress("DEPRECATION")
                val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                devices.firstOrNull()?.let { deviceRouteLabel(it) }
            }.getOrNull()
        return if (deviceLabel.isNullOrBlank()) {
            fallbackRouteKey(sampleRate)
        } else {
            "${deviceLabel}_sr_${sampleRate.coerceAtLeast(0)}"
        }
    }

    private fun fallbackRouteKey(sampleRate: Int): String =
        "unknown_route_sr_${sampleRate.coerceAtLeast(0)}"

    private fun deviceRouteLabel(device: AudioDeviceInfo): String {
        val typeName =
            when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "builtin_speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "builtin_earpiece"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "wired_headset"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "wired_headphones"
                AudioDeviceInfo.TYPE_USB_DEVICE -> "usb_device"
                AudioDeviceInfo.TYPE_USB_HEADSET -> "usb_headset"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "bluetooth_a2dp"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "bluetooth_sco"
                else -> "device_type_${device.type}"
            }
        return typeName
    }
}
