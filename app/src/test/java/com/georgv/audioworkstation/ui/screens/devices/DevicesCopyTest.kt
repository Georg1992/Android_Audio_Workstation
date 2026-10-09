package com.georgv.audioworkstation.ui.screens.devices

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.capability.CapabilityProfileState
import org.junit.Assert.assertEquals
import org.junit.Test

class DevicesCopyTest {
    @Test
    fun mapsProfileStates() {
        assertEquals(R.string.devices_profile_empty, devicesProfileStateRes(CapabilityProfileState.EMPTY))
        assertEquals(
            R.string.devices_profile_high_latency,
            devicesProfileStateRes(CapabilityProfileState.HIGH_LATENCY_ROUTE),
        )
    }

    @Test
    fun mapsWarningsAndMissingFields() {
        assertEquals(R.string.devices_warning_bluetooth, devicesWarningRes("Bluetooth route"))
        assertEquals(R.string.devices_missing_startup_metrics, devicesMissingFieldRes("startup_metrics"))
    }

    @Test
    fun replacesTheUnspecifiedBackendLabel() {
        assertEquals("Не задан", devicesBackendLabel("Unspecified", "Не задан"))
        assertEquals("AAudio", devicesBackendLabel("AAudio", "Не задан"))
    }
}
