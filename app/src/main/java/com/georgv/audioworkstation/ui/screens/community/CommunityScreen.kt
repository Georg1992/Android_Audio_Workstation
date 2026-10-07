package com.georgv.audioworkstation.ui.screens.community

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppOpacity
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityScreen(
    onBack: () -> Unit,
    vm: CommunityViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }

    ScreenScaffold(
        title = stringResource(R.string.screen_community),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Bg)
                .padding(padding)
                .padding(Dimens.ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap),
        ) {
            val signedInEmail = state.signedInEmail
            if (signedInEmail == null) {
                CommunityField(
                    label = stringResource(R.string.community_email_label),
                    value = email,
                    enabled = !state.busy,
                    keyboardType = KeyboardType.Email,
                    onValueChange = { email = it },
                )
                CommunityField(
                    label = stringResource(R.string.community_password_label),
                    value = password,
                    enabled = !state.busy,
                    keyboardType = KeyboardType.Password,
                    onValueChange = { password = it },
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Dimens.Gap),
                ) {
                    CommunityAction(
                        text = stringResource(R.string.community_sign_in),
                        fillColor = AppColors.Green,
                        enabled = !state.busy,
                        onClick = { vm.signIn(email, password) },
                    )
                    CommunityAction(
                        text = stringResource(R.string.community_create_account),
                        fillColor = AppColors.SurfacePanel,
                        enabled = !state.busy,
                        onClick = { vm.createAccount(email, password) },
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.community_signed_in, signedInEmail),
                    style = AppText.TileTitle,
                    color = AppColors.Line,
                )
                CommunityAction(
                    text = stringResource(R.string.community_sign_out),
                    fillColor = AppColors.SurfacePanel,
                    enabled = !state.busy,
                    onClick = vm::signOut,
                )
            }
        }
    }
}

@Composable
private fun CommunityField(
    label: String,
    value: String,
    enabled: Boolean,
    keyboardType: KeyboardType,
    onValueChange: (String) -> Unit,
) {
    Text(text = label, style = AppText.TileTitle, color = AppColors.Text)
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        visualTransformation = if (keyboardType == KeyboardType.Password) {
            PasswordVisualTransformation()
        } else {
            VisualTransformation.None
        },
        modifier = Modifier.fillMaxWidth(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = AppColors.SurfacePanel,
            unfocusedContainerColor = AppColors.SurfacePanel,
            disabledContainerColor = AppColors.SurfacePanel,
            focusedTextColor = AppColors.Line,
            unfocusedTextColor = AppColors.Line,
            disabledTextColor = AppColors.Line,
            focusedIndicatorColor = AppColors.Line,
            unfocusedIndicatorColor = AppColors.Line,
            disabledIndicatorColor = AppColors.Line,
            cursorColor = AppColors.Line,
        ),
    )
}

@Composable
private fun CommunityAction(
    text: String,
    fillColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    Surface(
        onClick = onClick,
        enabled = enabled,
        color = AppColors.SurfacePanel,
        shadowElevation = Dimens.Stroke,
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(fillColor)
                .border(Dimens.Stroke, AppColors.Line, shape)
                .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.TileInnerPadding),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text(
                text = text,
                style = AppText.TileTitle,
                color = AppColors.Line,
                modifier = Modifier.alpha(if (enabled) 1f else AppOpacity.disabled),
            )
        }
    }
}
