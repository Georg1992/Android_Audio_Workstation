package com.georgv.audioworkstation.ui.screens.community

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.screens.common.PlaceholderScreen

@Composable
fun CommunityScreen(onBack: () -> Unit) {
    PlaceholderScreen(
        title = stringResource(R.string.screen_community),
        body = "Community Screen",
        onBack = onBack
    )
}
