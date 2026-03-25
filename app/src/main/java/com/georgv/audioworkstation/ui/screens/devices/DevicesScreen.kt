package com.georgv.audioworkstation.ui.screens.devices

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DevicesScreen(onBack: () -> Unit) {
    ScreenScaffold(title = "Devices", onBack = onBack) { padding ->
        Text(
            text = "Devices Screen",
            modifier = Modifier
                .padding(padding)
                .padding(Dimens.ScreenContentPadding)
        )
    }
}
