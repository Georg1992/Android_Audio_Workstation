package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun ScreenScaffold(
    title: String = "",
    onBack: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    titleContent: @Composable (() -> Unit)? = null,
    topBarAlertMessage: String? = null,
    topBarAlertColor: Color = AppColors.Red,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit
) {
    val topBarContainerColor = if (topBarAlertMessage != null) topBarAlertColor else AppColors.Bg

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = AppColors.Bg,
        snackbarHost = snackbarHost,
        topBar = {
            CompactScreenTopBar(
                topBarContainerColor = topBarContainerColor,
                topBarAlertMessage = topBarAlertMessage,
                title = title,
                titleContent = titleContent,
                onBack = onBack,
                actions = actions,
            )
        },
    ) { padding ->
        content(padding)
    }
}

@Composable
private fun CompactScreenTopBar(
    topBarContainerColor: Color,
    topBarAlertMessage: String?,
    title: String,
    titleContent: @Composable (() -> Unit)?,
    onBack: (() -> Unit)?,
    actions: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(topBarContainerColor)
                .statusBarsPadding(),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(Dimens.TopBarHeight),
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier.size(Dimens.TopBarHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    if (onBack != null) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = AppColors.Line,
                            modifier =
                                Modifier
                                    .size(Dimens.TopBarHeight)
                                    .clickable(
                                        interactionSource = remember { MutableInteractionSource() },
                                        indication = null,
                                        onClick = onBack,
                                    )
                                    .padding(Dimens.TopBarNavIconInset),
                        )
                    }
                }
                Box(modifier = Modifier.weight(1f))
                Row(
                    modifier = Modifier.height(Dimens.TopBarHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    content = actions,
                )
            }
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = Dimens.TopBarHeight),
                contentAlignment = Alignment.Center,
            ) {
                if (topBarAlertMessage != null) {
                    Text(
                        text = topBarAlertMessage,
                        style = AppText.TopBarTitle,
                        color = AppColors.Line,
                        maxLines = 1,
                    )
                } else {
                    titleContent?.invoke() ?: Text(
                        text = title,
                        style = AppText.TopBarTitle,
                        color = AppColors.Line,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}
