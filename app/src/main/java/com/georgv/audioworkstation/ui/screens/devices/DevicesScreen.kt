package com.georgv.audioworkstation.ui.screens.devices

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.screens.common.PlaceholderScreen

@Composable
fun DevicesScreen(onBack: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.screen_devices),
        body = "Devices Screen",
        onBack = onBack
    )
}
