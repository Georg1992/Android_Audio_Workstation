package com.georgv.audioworkstation.ui.screens.devices

import android.media.AudioDeviceInfo

internal enum class PhoneAudioRole {
    Output,
    Input,
}

internal enum class PhoneAudioKind {
    Integrated,
    Bluetooth,
    Wired,
    Usb,
}

internal data class PhoneAudioDeviceFacts(
    val id: Int,
    val type: Int,
    val productName: String,
    val sink: Boolean,
    val source: Boolean,
)

internal data class PhoneAudioEndpoint(
    val id: Int,
    val role: PhoneAudioRole,
    val type: Int,
    val productName: String,
    val active: Boolean,
)

internal data class PhoneAudioGroup(
    val kind: PhoneAudioKind,
    val endpoints: List<PhoneAudioEndpoint>,
)

internal data class PhoneAudioSection(
    val role: PhoneAudioRole,
    val groups: List<PhoneAudioGroup>,
)

internal fun phoneAudioSections(
    devices: List<PhoneAudioDeviceFacts>,
    activeOutputIds: Set<Int>,
    activeInputIds: Set<Int>,
    chosenOutputId: Int?,
    chosenInputId: Int?,
): List<PhoneAudioSection> {
    val endpoints = phoneAudioEndpoints(devices, activeOutputIds, activeInputIds)
    return listOf(
        phoneAudioSection(PhoneAudioRole.Output, endpoints, chosenOutputId),
        phoneAudioSection(PhoneAudioRole.Input, endpoints, chosenInputId),
    )
}

private fun phoneAudioEndpoints(
    devices: List<PhoneAudioDeviceFacts>,
    activeOutputIds: Set<Int>,
    activeInputIds: Set<Int>,
): List<PhoneAudioEndpoint> {
    val endpoints = ArrayList<PhoneAudioEndpoint>()
    for (device in devices) {
        if (phoneAudioKind(device.type) == null) continue
        if (device.sink) {
            endpoints += device.toEndpoint(PhoneAudioRole.Output, device.id in activeOutputIds)
        }
        if (device.source) {
            endpoints += device.toEndpoint(PhoneAudioRole.Input, device.id in activeInputIds)
        }
    }
    return endpoints
}

private fun phoneAudioSection(
    role: PhoneAudioRole,
    endpoints: List<PhoneAudioEndpoint>,
    chosenId: Int?,
): PhoneAudioSection {
    val rows = endpoints.filter { it.role == role }.showingChoice(chosenId)
    val groups = ArrayList<PhoneAudioGroup>()
    for (kind in PhoneAudioKind.entries) {
        val inKind = rows.filter { phoneAudioKind(it.type) == kind }
        if (inKind.isNotEmpty()) {
            groups += PhoneAudioGroup(kind, inKind)
        }
    }
    return PhoneAudioSection(role, groups)
}

private fun List<PhoneAudioEndpoint>.showingChoice(chosenId: Int?): List<PhoneAudioEndpoint> {
    if (chosenId == null || none { it.id == chosenId }) return this
    return map { it.copy(active = it.id == chosenId) }
}

private fun PhoneAudioDeviceFacts.toEndpoint(
    role: PhoneAudioRole,
    active: Boolean,
): PhoneAudioEndpoint = PhoneAudioEndpoint(
    id = id,
    role = role,
    type = type,
    productName = productName,
    active = active,
)

private fun phoneAudioKind(type: Int): PhoneAudioKind? = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    AudioDeviceInfo.TYPE_BUILTIN_MIC,
    -> PhoneAudioKind.Integrated
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    -> PhoneAudioKind.Bluetooth
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    -> PhoneAudioKind.Wired
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> PhoneAudioKind.Usb
    else -> null
}
