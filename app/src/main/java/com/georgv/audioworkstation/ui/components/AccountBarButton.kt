package com.georgv.audioworkstation.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.ui.navigation.LoginRoute
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@Composable
fun AccountBarButton() {
    val bar = LocalAccountBar.current
    val name = bar.state.name
    if (!bar.state.known) return
    if (name == null) {
        val recording = LocalRecordingBlocksLogin.current
        BarLabel(
            text = stringResource(R.string.bar_login),
            enabled = LoginRoute.allowedDuring(recording),
            onClick = bar.openLogin,
        )
    } else {
        ProfileMenu(name = name, onSignOut = bar.signOut)
    }
}

@Composable
private fun ProfileMenu(name: String, onSignOut: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        BarLabel(text = name, onClick = { expanded = true })
        AppDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = {
                    Text(
                        text = stringResource(R.string.community_sign_out),
                        color = AppColors.Line,
                        style = AppText.TopBarTitle,
                    )
                },
                onClick = {
                    expanded = false
                    onSignOut()
                },
            )
        }
    }
}

@Composable
private fun BarLabel(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Box(
        modifier =
            Modifier
                .height(Dimens.TopBarHeight)
                .widthIn(min = Dimens.TopBarHeight, max = Dimens.AccountBarMaxWidth)
                .alpha(if (enabled) 1f else AppOpacity.disabled)
                .clickable(
                    enabled = enabled,
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = Dimens.PanelPadding),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = AppText.TopBarTitle,
            color = AppColors.Line,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            softWrap = false,
        )
    }
}

internal data class AccountBarController(
    val state: AccountBarUiState,
    val openLogin: () -> Unit,
    val signOut: () -> Unit,
)

internal val LocalAccountBar =
    staticCompositionLocalOf<AccountBarController> {
        error("Account bar is missing")
    }

internal val LocalRecordingBlocksLogin = staticCompositionLocalOf { false }
