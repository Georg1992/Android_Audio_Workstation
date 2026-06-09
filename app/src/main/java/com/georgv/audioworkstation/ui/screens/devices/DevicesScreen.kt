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
import com.georgv.audioworkstation.core.audio.capability.DeviceLatencySummary
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.Dimens
import java.util.Locale

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
                Text("Refresh")
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
            Text("Route latency (measurement only — compensation disabled)")
            when {
                state.loading -> CircularProgressIndicator()
                state.error != null -> Text("Error: ${state.error}")
                state.summary != null -> LatencySummaryContent(state.summary!!)
                else -> Text("No latency data for current route.")
            }
        }
    }
}

@Composable
private fun LatencySummaryContent(summary: DeviceLatencySummary) {
    SummaryRow("Route", summary.routeKey)
    SummaryRow("Sample rate", "${summary.sampleRate} Hz")
    SummaryRow("Profile state", summary.profileState.name)
    SummaryRow("Data complete", summary.dataComplete.toString())
    SummaryRow("Output floor (median)", formatMs(summary.outputMedianMs))
    SummaryRow("Output floor (p95)", formatMs(summary.outputP95Ms))
    SummaryRow("Capture delay (median)", formatMs(summary.inputCaptureMedianMs))
    SummaryRow("Round trip (median)", formatMs(summary.roundTripMedianMs))
    SummaryRow("Jitter (median)", formatMs(summary.jitterMedianMs))
    SummaryRow("App output overhead (p95)", formatMs(summary.appAddedOutputP95Ms))
    SummaryRow("App input overhead (p95)", formatMs(summary.appAddedInputP95Ms))
    SummaryRow("Low latency output", summary.lowLatencyOutputGranted.toString())
    SummaryRow("Low latency input", summary.lowLatencyInputGranted.toString())
    SummaryRow("Best known backend", summary.bestKnownBackend)
    SummaryRow("High latency route", summary.highLatencyRoute.toString())
    SummaryRow("Data confidence", String.format(Locale.US, "%.2f", summary.dataConfidence))
    if (summary.missingData.isNotEmpty()) {
        SummaryRow("Missing data", summary.missingData.joinToString(", "))
    }
    if (summary.warnings.isNotEmpty()) {
        Text("Warnings:")
        summary.warnings.forEach { warning ->
            Text("• $warning")
        }
    }
    if (summary.backendInventory.isNotEmpty()) {
        Text("Backend inventory:")
        summary.backendInventory.forEach { backend ->
            Text(
                "• ${backend.direction} ${backend.audioApi} " +
                    "perf=${backend.performanceMode} granted=${backend.performanceModeGranted} " +
                    "buf=${backend.bufferSizeFrames} latency=${formatMs(backend.measuredLatencyMs)}",
            )
        }
    }
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

private fun formatMs(value: Double?): String =
    if (value != null && value.isFinite() && value >= 0.0) {
        String.format(Locale.US, "%.1f ms", value)
    } else {
        "unknown"
    }
