package com.georgv.audioworkstation.ui.screens.projects

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import com.georgv.audioworkstation.ui.components.ScreenScaffold
import com.georgv.audioworkstation.ui.components.TrackCard
import com.georgv.audioworkstation.ui.components.TransportPanel
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

    ScreenScaffold(
        title = state.project?.name ?: "Project",
        onBack = onBack
    ) { padding ->

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(12.dp)
        ) {

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 76.dp), // место под TransportPanel
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item {
                    Text("Tracks: ${state.tracks.size}")
                }

                items(
                    items = state.tracks,
                    key = { it.id }
                ) { track ->
                    TrackCard(
                        title = track.name ?: "Track",
                        isSelected = state.selectedTrackIds.contains(track.id),
                        isRecording = state.recordingTrackId == track.id,
                        onClick = { vm.toggleSelect(track.id) }
                    )
                }
            }

            TransportPanel(
                isRecording = state.recordingTrackId != null,
                onPlay = { /* позже */ },
                onStop = { vm.onStopPressed() },
                onRecord = { vm.onRecordPressed(projectId) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}



