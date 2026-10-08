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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
    onSignedIn: () -> Unit,
    vm: CommunityViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }

    val place = communityDestination(state.sessionKnown, state.signedInEmail != null)
    LaunchedEffect(place) {
        if (place == CommunityDestination.Home) onSignedIn()
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
            when (place) {
                CommunityDestination.SignIn ->
                    if (state.awaitingCode) Confirmation(state, vm) else Credentials(state, vm)
                CommunityDestination.Pending, CommunityDestination.Home -> Unit
            }
        }
    }
}

@Composable
private fun Confirmation(state: CommunityUiState, vm: CommunityViewModel) {
    Text(
        text = stringResource(R.string.community_check_email),
        style = AppText.TileTitle,
        color = AppColors.Line,
    )
    CommunityField(
        value = state.confirmationCode,
        onValueChange = vm::onCodeChange,
        label = stringResource(R.string.community_code_label),
        keyboardType = KeyboardType.Number,
        enabled = !state.busy,
    )
    CommunityAction(
        text = stringResource(R.string.community_confirm),
        fillColor = AppColors.Green,
        enabled = !state.busy,
        onClick = vm::confirm,
    )
    CommunityAction(
        text = stringResource(R.string.community_sign_in),
        fillColor = AppColors.SurfacePanel,
        enabled = !state.busy,
        onClick = vm::showSignIn,
    )
}

@Composable
private fun Credentials(state: CommunityUiState, vm: CommunityViewModel) {
    Text(
        text = stringResource(R.string.community_sign_in_hint),
        style = AppText.TileTitle,
        color = AppColors.Line,
    )
    CommunityField(
        value = state.email,
        onValueChange = vm::onEmailChange,
        label = stringResource(R.string.community_email_label),
        keyboardType = KeyboardType.Email,
        enabled = !state.busy,
    )
    CommunityField(
        value = state.password,
        onValueChange = vm::onPasswordChange,
        label = stringResource(R.string.community_password_label),
        keyboardType = KeyboardType.Password,
        hidden = true,
        enabled = !state.busy,
    )
    CommunityField(
        value = state.confirmPassword,
        onValueChange = vm::onConfirmPasswordChange,
        label = stringResource(R.string.community_confirm_password_label),
        keyboardType = KeyboardType.Password,
        hidden = true,
        enabled = !state.busy,
    )
    CommunityAction(
        text = stringResource(R.string.community_sign_in),
        fillColor = AppColors.Green,
        enabled = !state.busy,
        onClick = vm::signIn,
    )
    CommunityAction(
        text = stringResource(R.string.community_register),
        fillColor = AppColors.SurfacePanel,
        enabled = !state.busy,
        onClick = vm::register,
    )
    CommunityAction(
        text = stringResource(R.string.community_gmail),
        fillColor = AppColors.SurfacePanel,
        enabled = !state.busy,
        onClick = vm::signInWithGoogle,
    )
}

@Composable
private fun CommunityField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    enabled: Boolean,
    hidden: Boolean = false,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        label = { Text(text = label) },
        visualTransformation = if (hidden) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
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
            focusedLabelColor = AppColors.Line,
            unfocusedLabelColor = AppColors.iconMuted,
        ),
    )
}

@Composable
internal fun CommunityAction(
    text: String,
    fillColor: Color,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
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
