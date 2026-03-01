package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.Dimens
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectScreen(
    projectId: String,
    quickRecord: Boolean,
    onBack: () -> Unit,
    vm: ProjectViewModel = hiltViewModel()
) {
    LaunchedEffect(projectId, quickRecord) {
        vm.bind(projectId)

        val projectName = if (quickRecord) {
            val time = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"))
            "QuickRec_$time"
        } else {
            "New Project"
        }

        vm.ensureProjectExists(projectId, projectName)

        if (quickRecord) {
            vm.addTrack(projectId)
        }
    }

    val state by vm.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Keep UI stable; reorder will use positions
    val tracks = remember(state.tracks) {
        state.tracks.sortedBy { it.position }
    }

    ScreenScaffold(title = state.project?.name ?: "Project", onBack = onBack) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val panelShape = RoundedCornerShape(Dimens.TileRadius)

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
                    .height(Dimens.PanelPlaceholderHeight)
                    .clip(panelShape)
                    .background(AppColors.Bg)
                    .border(Dimens.Stroke, AppColors.Line, panelShape)
            )

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.Gap)
            ) {
                items(
                    items = tracks,
                    key = { it.id }
                ) { track ->
                    var gainLocal by remember(track.id) { mutableFloatStateOf(track.gain) }

                    TrackCard(
                        title = track.name ?: "Track",
                        isSelected = state.selectedTrackIds.contains(track.id),
                        isRecording = state.recordingTrackId == track.id,
                        gain = gainLocal,
                        onGainChange = { gainLocal = it },
                        onClick = { vm.toggleSelect(track.id) },
                        onDelete = { vm.deleteTrack(track.id) }
                    )
                }
            }

            TransportPanel(
                isRecording = state.recordingTrackId != null,
                isPlaying = state.playingTrackIds.isNotEmpty(),
                isPlayEnabled = state.isPlayEnabled,
                isStopEnabled = state.isStopEnabled,
                onPlay = { vm.onPlayPressed() },
                onStop = { vm.onStopPressed() },
                onRecord = { vm.onRecordPressed(projectId) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding)
            )
        }
    }
}
