package com.georgv.audioworkstation.ui.screens.devices

import androidx.annotation.StringRes
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.capability.CapabilityProfileState

private const val UnspecifiedBackend = "Unspecified"

@StringRes
fun devicesProfileStateRes(state: CapabilityProfileState): Int = when (state) {
    CapabilityProfileState.EMPTY -> R.string.devices_profile_empty
    CapabilityProfileState.PARTIAL -> R.string.devices_profile_partial
    CapabilityProfileState.MEASURED -> R.string.devices_profile_measured
    CapabilityProfileState.VALIDATED -> R.string.devices_profile_validated
    CapabilityProfileState.INCONSISTENT -> R.string.devices_profile_inconsistent
    CapabilityProfileState.HIGH_LATENCY_ROUTE -> R.string.devices_profile_high_latency
}

@StringRes
fun devicesWarningRes(warning: String): Int = when (warning) {
    "Low latency denied" -> R.string.devices_warning_low_latency_denied
    "Bluetooth route" -> R.string.devices_warning_bluetooth
    "output latency high" -> R.string.devices_warning_output_latency_high
    "capture delay unknown" -> R.string.devices_warning_capture_delay_unknown
    "measurement inconsistent" -> R.string.devices_warning_measurement_inconsistent
    else -> error("Unknown latency warning: $warning")
}

@StringRes
fun devicesMissingFieldRes(field: String): Int = when (field) {
    "profile" -> R.string.devices_missing_profile
    "output_stream_config" -> R.string.devices_missing_output_stream_config
    "input_stream_config" -> R.string.devices_missing_input_stream_config
    "output_hardware_floor" -> R.string.devices_missing_output_hardware_floor
    "true_capture_delay" -> R.string.devices_missing_true_capture_delay
    "round_trip" -> R.string.devices_missing_round_trip
    "jitter" -> R.string.devices_missing_jitter
    "startup_metrics" -> R.string.devices_missing_startup_metrics
    else -> error("Unknown missing latency field: $field")
}

fun devicesBackendLabel(name: String, unspecified: String): String =
    if (name == UnspecifiedBackend) unspecified else name
