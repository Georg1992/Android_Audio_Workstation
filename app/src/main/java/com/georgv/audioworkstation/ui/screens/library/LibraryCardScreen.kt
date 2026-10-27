package com.georgv.audioworkstation.ui.screens.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.screens.community.CommunityAction
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryCardScreen(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    vm: LibraryCardViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.chooseImage(uri)
    }
    val enabled = state.loaded && !state.busy

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }
    LaunchedEffect(vm) {
        vm.savedEvent.collect { onSaved() }
    }

    ScreenScaffold(
        title = stringResource(R.string.screen_library_card),
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
            CardField(
                value = state.title,
                onValueChange = vm::setTitle,
                label = stringResource(R.string.create_project_name_label),
                enabled = enabled,
            )
            CardField(
                value = state.tags,
                onValueChange = vm::setTags,
                label = stringResource(R.string.library_card_tags_hint),
                enabled = enabled,
            )
            if (state.imageChosen) {
                Text(text = stringResource(R.string.library_card_image_selected))
            }
            CommunityAction(
                text = stringResource(R.string.library_card_choose_image),
                fillColor = AppColors.SurfacePanel,
                enabled = enabled,
                onClick = { picker.launch("image/*") },
            )
            CommunityAction(
                text = stringResource(R.string.library_card_save),
                fillColor = AppColors.Green,
                enabled = enabled,
                onClick = vm::save,
            )
        }
    }
}

@Composable
private fun CardField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        enabled = enabled,
        label = { Text(text = label) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
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
