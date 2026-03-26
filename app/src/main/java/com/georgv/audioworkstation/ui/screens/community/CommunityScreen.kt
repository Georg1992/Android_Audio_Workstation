package com.georgv.audioworkstation.ui.screens.community

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(onBack: () -> Unit) {
    ScreenScaffold(title = stringResource(R.string.screen_community), onBack = onBack) { padding ->
        Text(
            text = "Community Screen",
            modifier = Modifier
                .padding(padding)
                .padding(Dimens.ScreenContentPadding)
        )
    }
}
