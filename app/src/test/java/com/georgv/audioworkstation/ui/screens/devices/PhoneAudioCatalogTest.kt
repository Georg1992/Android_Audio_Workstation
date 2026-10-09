package com.georgv.audioworkstation.ui.screens.devices

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAudioCatalogTest {
    @Test
    fun listsIntegratedSpeakerAndMicrophone() {
        val sections = phoneAudioSections(
            devices = listOf(
                facts(id = 2, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink = true),
                facts(id = 15, type = AudioDeviceInfo.TYPE_BUILTIN_MIC, source = true),
            ),
            activeOutputIds = setOf(2),
            activeInputIds = emptySet(),
            chosenOutputId = null,
            chosenInputId = null,
        )

        val output = sections.section(PhoneAudioRole.Output)
        val input = sections.section(PhoneAudioRole.Input)
        assertEquals(listOf(PhoneAudioKind.Integrated), output.groups.map { it.kind })
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, output.groups.single().endpoints.single().type)
        assertTrue(output.groups.single().endpoints.single().active)
        assertEquals(AudioDeviceInfo.TYPE_BUILTIN_MIC, input.groups.single().endpoints.single().type)
        assertEquals(false, input.groups.single().endpoints.single().active)
    }

    @Test
    fun keepsBluetoothProductNameAndSkipsVirtualDevices() {
        val sections = phoneAudioSections(
            devices = listOf(
                facts(
                    id = 8,
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    productName = "Pixel Buds",
                    sink = true,
                ),
                facts(id = 9, type = AudioDeviceInfo.TYPE_HDMI, sink = true),
                facts(id = 10, type = AudioDeviceInfo.TYPE_TELEPHONY, sink = true, source = true),
            ),
            activeOutputIds = setOf(8),
            activeInputIds = emptySet(),
            chosenOutputId = null,
            chosenInputId = null,
        )

        val output = sections.section(PhoneAudioRole.Output)
        assertEquals(listOf(PhoneAudioKind.Bluetooth), output.groups.map { it.kind })
        assertEquals("Pixel Buds", output.groups.single().endpoints.single().productName)
        assertTrue(sections.section(PhoneAudioRole.Input).groups.isEmpty())
    }

    @Test
    fun splitsHeadsetIntoOutputAndInput() {
        val sections = phoneAudioSections(
            devices = listOf(
                facts(
                    id = 3,
                    type = AudioDeviceInfo.TYPE_WIRED_HEADSET,
                    productName = "Headset",
                    sink = true,
                    source = true,
                ),
            ),
            activeOutputIds = emptySet(),
            activeInputIds = setOf(3),
            chosenOutputId = null,
            chosenInputId = null,
        )

        val output = sections.section(PhoneAudioRole.Output).groups.single().endpoints.single()
        val input = sections.section(PhoneAudioRole.Input).groups.single().endpoints.single()
        assertEquals(3, output.id)
        assertEquals(3, input.id)
        assertEquals(PhoneAudioRole.Output, output.role)
        assertEquals(PhoneAudioRole.Input, input.role)
        assertEquals(false, output.active)
        assertTrue(input.active)
    }

    @Test
    fun chosenDeviceReplacesTheSystemRouteMark() {
        val sections = phoneAudioSections(
            devices = listOf(
                facts(id = 2, type = AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, sink = true),
                facts(
                    id = 8,
                    type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                    productName = "Buds",
                    sink = true,
                ),
            ),
            activeOutputIds = setOf(2),
            activeInputIds = emptySet(),
            chosenOutputId = 8,
            chosenInputId = null,
        )

        val rows = sections.section(PhoneAudioRole.Output).groups.flatMap { it.endpoints }
        assertEquals(false, rows.first { it.id == 2 }.active)
        assertTrue(rows.first { it.id == 8 }.active)
    }

    private fun List<PhoneAudioSection>.section(role: PhoneAudioRole): PhoneAudioSection =
        first { it.role == role }

    private fun facts(
        id: Int,
        type: Int,
        productName: String = "",
        sink: Boolean = false,
        source: Boolean = false,
    ) = PhoneAudioDeviceFacts(
        id = id,
        type = type,
        productName = productName,
        sink = sink,
        source = source,
    )
}
