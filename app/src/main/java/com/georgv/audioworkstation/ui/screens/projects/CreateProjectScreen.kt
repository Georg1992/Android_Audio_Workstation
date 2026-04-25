package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import com.georgv.audioworkstation.core.ui.resolve
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ProjectSampleRate
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.theme.Alphas
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProjectScreen(
    onBack: () -> Unit,
    onProjectCreated: (String) -> Unit,
    vm: CreateProjectViewModel = hiltViewModel()
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }

    LaunchedEffect(vm) {
        vm.createdProjects.collect(onProjectCreated)
    }

    ScreenScaffold(
        title = stringResource(R.string.screen_create_project),
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(AppColors.Bg)
                .padding(padding)
                .padding(Dimens.ScreenContentPadding),
            verticalArrangement = Arrangement.spacedBy(Dimens.Gap)
        ) {
            Text(
                text = stringResource(R.string.create_project_name_label),
                style = AppText.TileTitle,
                color = AppColors.Text
            )

            TextField(
                value = state.projectName,
                onValueChange = vm::onProjectNameChange,
                singleLine = true,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(text = stringResource(R.string.create_project_name_placeholder))
                },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    keyboardType = KeyboardType.Text,
                    imeAction = ImeAction.Done
                ),
                keyboardActions = KeyboardActions(
                    onDone = {
                        focusManager.clearFocus()
                        vm.createProject()
                    }
                ),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = AppColors.Bg,
                    unfocusedContainerColor = AppColors.Bg,
                    disabledContainerColor = AppColors.Bg,
                    focusedTextColor = AppColors.Line,
                    unfocusedTextColor = AppColors.Line,
                    disabledTextColor = AppColors.Line,
                    focusedIndicatorColor = AppColors.Line,
                    unfocusedIndicatorColor = AppColors.Line,
                    disabledIndicatorColor = AppColors.Line,
                    cursorColor = AppColors.Line
                )
            )

            Spacer(Modifier.height(Dimens.Gap))

            Text(
                text = stringResource(R.string.create_project_sample_rate_label),
                style = AppText.TileTitle,
                color = AppColors.Text
            )

            SampleRateSelector(
                selected = state.sampleRate,
                enabled = !state.isSaving,
                onSelect = vm::onSampleRateChange
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)
            ) {
                CreateProjectActionButton(
                    text = stringResource(R.string.action_cancel),
                    fillColor = AppColors.Bg,
                    onClick = onBack,
                    enabled = !state.isSaving
                )

                CreateProjectActionButton(
                    text = stringResource(R.string.action_create_project),
                    fillColor = AppColors.Green,
                    onClick = {
                        focusManager.clearFocus()
                        vm.createProject()
                    },
                    enabled = !state.isSaving
                )
            }
        }
    }
}

@Composable
private fun SampleRateSelector(
    selected: ProjectSampleRate,
    enabled: Boolean,
    onSelect: (ProjectSampleRate) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Dimens.Gap)
    ) {
        SampleRateOption(
            label = stringResource(R.string.create_project_sample_rate_44100),
            caption = stringResource(R.string.create_project_sample_rate_44100_caption),
            isSelected = selected == ProjectSampleRate.RATE_44_100,
            enabled = enabled,
            onClick = { onSelect(ProjectSampleRate.RATE_44_100) },
            modifier = Modifier.weight(1f)
        )
        SampleRateOption(
            label = stringResource(R.string.create_project_sample_rate_48000),
            caption = stringResource(R.string.create_project_sample_rate_48000_caption),
            isSelected = selected == ProjectSampleRate.RATE_48_000,
            enabled = enabled,
            onClick = { onSelect(ProjectSampleRate.RATE_48_000) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SampleRateOption(
    label: String,
    caption: String,
    isSelected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)
    val fillColor: Color = if (isSelected) AppColors.Accent else AppColors.Bg

    Column(
        modifier = modifier
            .clip(shape)
            .background(fillColor)
            .border(Dimens.Stroke, AppColors.Line, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.TileInnerPadding)
            .alpha(if (enabled) 1f else Alphas.Disabled),
        verticalArrangement = Arrangement.spacedBy(Dimens.TightGap),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = label,
            style = AppText.TileTitle,
            color = AppColors.Line,
            textAlign = TextAlign.Center
        )
        Text(
            text = caption,
            style = AppText.TileSubtitle,
            color = AppColors.Line,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun CreateProjectActionButton(
    text: String,
    fillColor: Color,
    onClick: () -> Unit,
    enabled: Boolean
) {
    val shape = RoundedCornerShape(Dimens.MediumRadius)

    Surface(
        onClick = onClick,
        enabled = enabled,
        color = AppColors.Bg,
        shadowElevation = Dimens.Stroke
    ) {
        Row(
            modifier = Modifier
                .clip(shape)
                .background(fillColor)
                .border(Dimens.Stroke, AppColors.Line, shape)
                .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.TileInnerPadding),
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = text,
                style = AppText.TileTitle,
                color = AppColors.Line,
                modifier = Modifier.alpha(if (enabled) 1f else Alphas.Disabled)
            )
        }
    }
}
