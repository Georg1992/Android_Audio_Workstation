package com.georgv.audioworkstation.ui.screens.devices

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi

internal class PhoneAudioDevices(
    private val audioManager: AudioManager,
) {
    fun listing(chosenOutputId: Int?, chosenInputId: Int?): List<PhoneAudioSection> =
        phoneAudioSections(
            devices = connectedDevices(),
            activeOutputIds = routedIds(AudioAttributes.USAGE_MEDIA),
            activeInputIds = routedIds(AudioAttributes.USAGE_VOICE_COMMUNICATION),
            chosenOutputId = chosenOutputId,
            chosenInputId = chosenInputId,
        )

    fun select(deviceId: Int) {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            "Audio device selection requires Android 12"
        }
        selectCommunicationDevice(deviceId)
    }

    private fun connectedDevices(): List<PhoneAudioDeviceFacts> {
        val byId = LinkedHashMap<Int, PhoneAudioDeviceFacts>()
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            absorb(byId, device)
        }
        for (device in audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            absorb(byId, device)
        }
        return byId.values.toList()
    }

    private fun absorb(
        into: MutableMap<Int, PhoneAudioDeviceFacts>,
        device: AudioDeviceInfo,
    ) {
        val name = device.productName?.toString()?.trim().orEmpty()
        val existing = into[device.id]
        if (existing == null) {
            into[device.id] = PhoneAudioDeviceFacts(
                id = device.id,
                type = device.type,
                productName = name,
                sink = device.isSink,
                source = device.isSource,
            )
            return
        }
        into[device.id] = existing.copy(
            sink = existing.sink || device.isSink,
            source = existing.source || device.isSource,
            productName = existing.productName.ifBlank { name },
        )
    }

    private fun routedIds(usage: Int): Set<Int> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptySet()
        return routedIdsOnS(usage)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun routedIdsOnS(usage: Int): Set<Int> {
        val attributes = AudioAttributes.Builder().setUsage(usage).build()
        return audioManager.getAudioDevicesForAttributes(attributes).map { it.id }.toSet()
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun selectCommunicationDevice(deviceId: Int) {
        val device = audioManager.getDevices(AudioManager.GET_DEVICES_ALL)
            .firstOrNull { it.id == deviceId }
            ?: error("Audio device $deviceId is not connected")
        audioManager.setCommunicationDevice(device)
    }
}
