package com.georgv.audioworkstation.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.screens.common.PlaceholderScreen

@Composable
fun LibraryScreen(onBack: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.screen_library),
        body = "Library Screen",
        onBack = onBack
    )
}
