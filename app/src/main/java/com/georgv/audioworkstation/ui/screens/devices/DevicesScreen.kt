package com.georgv.audioworkstation.ui.screens.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.capability.BackendCapabilitySnapshot
import com.georgv.audioworkstation.core.audio.capability.DeviceLatencySummary
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = stringResource(R.string.screen_devices),
        onBack = onBack,
        actions = {
            TextButton(onClick = viewModel::refresh) {
                Text(stringResource(R.string.devices_refresh))
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .padding(Dimens.ScreenContentPadding)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            Text(stringResource(R.string.devices_route_latency))
            val errorDetail = state.errorDetail
            val summary = state.summary
            when {
                state.loading -> CircularProgressIndicator()
                state.loadFailed -> Text(stringResource(R.string.devices_load_failed))
                errorDetail != null -> Text(stringResource(R.string.devices_error, errorDetail))
                summary != null -> LatencySummaryContent(summary)
                else -> Text(stringResource(R.string.devices_no_latency))
            }
        }
    }
}

@Composable
private fun LatencySummaryContent(summary: DeviceLatencySummary) {
    val yes = stringResource(R.string.devices_yes)
    val no = stringResource(R.string.devices_no)
    SummaryRow(stringResource(R.string.devices_route), summary.routeKey)
    SummaryRow(
        stringResource(R.string.devices_sample_rate),
        stringResource(R.string.devices_sample_rate_value, summary.sampleRate),
    )
    SummaryRow(
        stringResource(R.string.devices_profile_state),
        stringResource(devicesProfileStateRes(summary.profileState)),
    )
    SummaryRow(stringResource(R.string.devices_data_complete), if (summary.dataComplete) yes else no)
    SummaryRow(stringResource(R.string.devices_output_floor_median), latencyText(summary.outputMedianMs))
    SummaryRow(stringResource(R.string.devices_output_floor_p95), latencyText(summary.outputP95Ms))
    SummaryRow(stringResource(R.string.devices_capture_delay_median), latencyText(summary.inputCaptureMedianMs))
    SummaryRow(stringResource(R.string.devices_round_trip_median), latencyText(summary.roundTripMedianMs))
    SummaryRow(stringResource(R.string.devices_jitter_median), latencyText(summary.jitterMedianMs))
    SummaryRow(stringResource(R.string.devices_app_output_overhead_p95), latencyText(summary.appAddedOutputP95Ms))
    SummaryRow(stringResource(R.string.devices_app_input_overhead_p95), latencyText(summary.appAddedInputP95Ms))
    SummaryRow(stringResource(R.string.devices_low_latency_output), if (summary.lowLatencyOutputGranted) yes else no)
    SummaryRow(stringResource(R.string.devices_low_latency_input), if (summary.lowLatencyInputGranted) yes else no)
    SummaryRow(
        stringResource(R.string.devices_best_backend),
        devicesBackendLabel(summary.bestKnownBackend, stringResource(R.string.devices_backend_unspecified)),
    )
    SummaryRow(stringResource(R.string.devices_high_latency_route), if (summary.highLatencyRoute) yes else no)
    SummaryRow(
        stringResource(R.string.devices_data_confidence),
        stringResource(R.string.devices_confidence_value, summary.dataConfidence),
    )
    if (summary.missingData.isNotEmpty()) {
        val missingLabels = ArrayList<String>(summary.missingData.size)
        for (field in summary.missingData) {
            missingLabels += stringResource(devicesMissingFieldRes(field))
        }
        SummaryRow(
            stringResource(R.string.devices_missing_data),
            missingLabels.joinToString(", "),
        )
    }
    if (summary.warnings.isNotEmpty()) {
        Text(stringResource(R.string.devices_warnings))
        for (warning in summary.warnings) {
            Text("• ${stringResource(devicesWarningRes(warning))}")
        }
    }
    if (summary.backendInventory.isNotEmpty()) {
        Text(stringResource(R.string.devices_backend_inventory))
        for (backend in summary.backendInventory) {
            Text("• ${BackendEntry(backend, yes, no)}")
        }
    }
}

@Composable
private fun BackendEntry(
    backend: BackendCapabilitySnapshot,
    yes: String,
    no: String,
): String {
    val granted = if (backend.performanceModeGranted) yes else no
    return stringResource(
        R.string.devices_backend_entry,
        backend.direction,
        backend.audioApi,
        backend.performanceMode,
        granted,
        backend.bufferSizeFrames,
        latencyText(backend.measuredLatencyMs),
    )
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Text(
        text = "$label: $value",
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun latencyText(value: Double?): String =
    if (value != null && value.isFinite() && value >= 0.0) {
        stringResource(R.string.devices_latency_ms, value)
    } else {
        stringResource(R.string.devices_latency_unknown)
    }
