package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TrackClipTimelinePositionEditor
import com.georgv.audioworkstation.ui.components.TrackClipTrimWaveformEditor
import com.georgv.audioworkstation.ui.components.TrackEditFxPlaceholderCard
import com.georgv.audioworkstation.ui.components.WaveformState
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens

private const val TrackEditWaveformSectionWeight = 0.52f
private const val TrackEditTimelineSectionWeight = 0.2f
private const val TrackEditFxSectionWeight = 0.28f
private const val TrackEditSectionTitleFontScale = 0.85f

@Composable
fun TrackEditScreen(
    onBack: () -> Unit,
    vm: TrackEditViewModel = hiltViewModel(),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(vm) {
        vm.userMessages.collect { message ->
            snackbarHostState.showSnackbar(message.resolve(context))
        }
    }

    ScreenScaffold(
        title = state.trackName,
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        when {
            state.trackMissing -> {
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.track_edit_track_missing),
                        style = AppText.TopBarTitle,
                        color = AppColors.Line,
                    )
                }
            }
            else -> {
                TrackEditContent(
                    state = state,
                    onTrimCommit = vm::commitTrimRegion,
                    onClipPositionCommit = vm::commitClipPosition,
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .background(AppColors.Bg)
                            .padding(padding)
                            .padding(horizontal = Dimens.TileInnerPadding),
                )
            }
        }
    }
}

@Composable
private fun TrackEditContent(
    state: TrackEditUiState,
    onTrimCommit: (trimStartMs: Long, trimEndMs: Long) -> Unit,
    onClipPositionCommit: (clipStartOffsetMs: Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(Dimens.PanelPadding),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(TrackEditWaveformSectionWeight),
        ) {
            when (val waveformState = state.waveformState) {
                is WaveformState.Ready ->
                    TrackClipTrimWaveformEditor(
                        peaks = waveformState.peaks,
                        sourceDurationMs = state.sourceDurationMs,
                        trimStartMs = state.trimStartMs,
                        trimEndMs = state.trimEndMs,
                        onTrimCommit = onTrimCommit,
                        modifier = Modifier.fillMaxSize(),
                    )
                WaveformState.Loading ->
                    TrackEditWaveformPlaceholder(stringResource(R.string.waveform_generating))
                WaveformState.Failed,
                WaveformState.NoWaveform,
                ->
                    TrackEditWaveformPlaceholder(stringResource(R.string.waveform_unavailable))
                is WaveformState.Importing,
                WaveformState.ImportFailed,
                ->
                    TrackEditWaveformPlaceholder(stringResource(R.string.waveform_unavailable))
            }
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(TrackEditTimelineSectionWeight),
        ) {
            Text(
                text = stringResource(R.string.track_edit_timeline_position),
                style = AppText.TopBarTitle.copy(fontSize = AppText.TopBarTitle.fontSize * TrackEditSectionTitleFontScale),
                color = AppColors.Line,
            )
            Spacer(Modifier.height(Dimens.SmallRadius))
            TrackClipTimelinePositionEditor(
                timelineLayoutDurationMs = state.timelineLayoutDurationMs,
                clipStartOffsetMs = state.clipStartOffsetMs,
                trimmedDurationMs = state.trimmedDurationMs,
                onClipPositionCommit = onClipPositionCommit,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .weight(TrackEditFxSectionWeight),
            verticalArrangement = Arrangement.spacedBy(Dimens.PanelPadding),
        ) {
            TrackEditFxPlaceholderCard(
                title = stringResource(R.string.track_edit_input_fx),
                modifier = Modifier.weight(1f),
            )
            TrackEditFxPlaceholderCard(
                title = stringResource(R.string.track_edit_output_fx),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun TrackEditWaveformPlaceholder(message: String) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(AppColors.Bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = AppText.TopBarTitle,
            color = AppColors.Line.copy(alpha = 0.7f),
        )
    }
}
