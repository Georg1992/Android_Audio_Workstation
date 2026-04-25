package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
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
        snackbarHost = snackbarHost,
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (topBarAlertMessage != null) {
                        Text(
                            text = topBarAlertMessage,
                            style = AppText.TopBarTitle,
                            color = AppColors.Line
                        )
                    } else {
                        titleContent?.invoke() ?: Text(
                            text = title,
                            style = AppText.TopBarTitle,
                            color = AppColors.Line
                        )
                    }
                },
                modifier = Modifier.height(Dimens.TopBarHeight),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = AppColors.Line
                            )
                        }
                    }
                },
                actions = actions,
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = topBarContainerColor,
                    scrolledContainerColor = topBarContainerColor,
                    navigationIconContentColor = AppColors.Line,
                    titleContentColor = AppColors.Line,
                    actionIconContentColor = AppColors.Line
                )
            )
        }
    ) { padding ->
        content(padding)
    }
}
