package com.georgv.audioworkstation.core.audio.capability

enum class AudioRouteType {
    SPEAKER,
    EARPIECE,
    WIRED,
    BLUETOOTH,
    USB,
    UNKNOWN,
    ;

    fun isHighLatencyRoute(): Boolean = this == BLUETOOTH

    companion object {
        fun fromRouteTypeLabel(label: String): AudioRouteType =
            when (label) {
                "builtin_speaker" -> SPEAKER
                "builtin_earpiece" -> EARPIECE
                "wired_headset", "wired_headphones" -> WIRED
                "bluetooth_a2dp", "bluetooth_sco" -> BLUETOOTH
                "usb_device", "usb_headset" -> USB
                else -> UNKNOWN
            }
    }
}
