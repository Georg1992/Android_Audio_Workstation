package com.georgv.audioworkstation.ui.screens.community

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityHomeScreen(
    onBack: () -> Unit,
    onSignedOut: () -> Unit,
    vm: CommunityHomeViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val place = communityDestination(state.sessionKnown, state.email != null)

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }

    LaunchedEffect(place) {
        if (place == CommunityDestination.SignIn) onSignedOut()
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
            val email = state.email
            if (email != null) {
                Text(
                    text = stringResource(R.string.community_signed_in, email),
                    style = AppText.TileTitle,
                    color = AppColors.Line,
                )
                Text(
                    text = stringResource(R.string.screen_community_placeholder_body),
                    style = AppText.TileSubtitle,
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
