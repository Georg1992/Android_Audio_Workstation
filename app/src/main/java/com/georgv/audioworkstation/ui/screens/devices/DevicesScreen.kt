package com.georgv.audioworkstation.ui.screens.devices

import android.media.AudioDeviceInfo
import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun DevicesScreen(
    onBack: () -> Unit,
    viewModel: DevicesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = stringResource(R.string.screen_devices),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(Dimens.ScreenContentPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            for (section in state.sections) {
                DeviceSection(section, viewModel::select)
            }
        }
    }
}

@Composable
private fun DeviceSection(
    section: PhoneAudioSection,
    onSelect: (PhoneAudioRole, Int) -> Unit,
) {
    Text(
        text = stringResource(roleLabelRes(section.role)),
        style = AppText.TileTitle,
        color = AppColors.Line,
    )
    for (group in section.groups) {
        Text(
            text = stringResource(kindLabelRes(group.kind)),
            style = AppText.TileSubtitle,
            color = AppColors.textSecondary,
        )
        for (endpoint in group.endpoints) {
            DeviceRow(group.kind, endpoint, onSelect)
        }
    }
}

@Composable
private fun DeviceRow(
    kind: PhoneAudioKind,
    endpoint: PhoneAudioEndpoint,
    onSelect: (PhoneAudioRole, Int) -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.TileRadius)
    val borderColor = if (endpoint.active) AppColors.Green else AppColors.Line
    val fill = if (endpoint.active) AppColors.SurfacePressed else AppColors.SurfacePanel
    Surface(
        onClick = { onSelect(endpoint.role, endpoint.id) },
        modifier = Modifier
            .fillMaxWidth()
            .border(Dimens.Stroke, borderColor, shape),
        color = fill,
        shape = shape,
    ) {
        Text(
            text = endpoint.title(kind),
            style = AppText.TileTitle,
            color = AppColors.Line,
            modifier = Modifier.padding(Dimens.TileInnerPadding),
        )
    }
}

@Composable
private fun PhoneAudioEndpoint.title(kind: PhoneAudioKind): String {
    if (kind != PhoneAudioKind.Integrated && productName.isNotBlank()) {
        return productName
    }
    return stringResource(typeLabelRes(type))
}

@StringRes
private fun roleLabelRes(role: PhoneAudioRole): Int = when (role) {
    PhoneAudioRole.Output -> R.string.devices_output
    PhoneAudioRole.Input -> R.string.devices_input
}

@StringRes
private fun kindLabelRes(kind: PhoneAudioKind): Int = when (kind) {
    PhoneAudioKind.Integrated -> R.string.devices_kind_integrated
    PhoneAudioKind.Bluetooth -> R.string.devices_kind_bluetooth
    PhoneAudioKind.Wired -> R.string.devices_kind_wired
    PhoneAudioKind.Usb -> R.string.devices_kind_usb
}

@StringRes
private fun typeLabelRes(type: Int): Int = when (type) {
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER,
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE,
    -> R.string.devices_speaker
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> R.string.devices_earpiece
    AudioDeviceInfo.TYPE_BUILTIN_MIC -> R.string.devices_microphone
    AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
    AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST,
    -> R.string.devices_headphones
    AudioDeviceInfo.TYPE_WIRED_HEADSET,
    AudioDeviceInfo.TYPE_BLE_HEADSET,
    AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_USB_HEADSET,
    -> R.string.devices_headset
    AudioDeviceInfo.TYPE_USB_DEVICE,
    AudioDeviceInfo.TYPE_USB_ACCESSORY,
    -> R.string.devices_kind_usb
    else -> error("Unsupported audio device type $type")
}
