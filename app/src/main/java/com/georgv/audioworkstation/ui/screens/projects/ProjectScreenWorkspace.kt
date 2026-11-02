package com.georgv.audioworkstation.ui.screens.projects

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ContentResolverAudioImportSource
import com.georgv.audioworkstation.core.content.resolveDisplayName
import com.georgv.audioworkstation.core.ui.resolve
import com.georgv.audioworkstation.ui.components.ImportAudioButton
import com.georgv.audioworkstation.ui.components.MixdownAudioButton
import com.georgv.audioworkstation.ui.components.TimelinePlayheadScrubberPanel
import com.georgv.audioworkstation.ui.components.TopBarAlertState
import com.georgv.audioworkstation.ui.components.TransportPanel
import com.georgv.audioworkstation.ui.components.formatTimelineDuration
import com.georgv.audioworkstation.ui.components.rememberAppLocaleScope
import com.georgv.audioworkstation.ui.diagnostics.WaveformRecompositionDiagnostics
import com.georgv.audioworkstation.ui.drag.DragController
import com.georgv.audioworkstation.ui.mixdown.ProjectMixdownViewModel
import com.georgv.audioworkstation.ui.theme.AppColors
import com.georgv.audioworkstation.ui.theme.AppText
import com.georgv.audioworkstation.ui.theme.Dimens
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ProjectScreenHeavyLayer(
    vm: ProjectViewModel,
    projectId: String,
    showWaveforms: Boolean,
    onOpenProject: (String) -> Unit,
    onConfirmMixdown: (String, Set<String>) -> Unit,
    onEditTrack: (String) -> Unit,
    topBarAlertState: TopBarAlertState,
    onTrackPagingSummaryChange: (String) -> Unit,
) {
    val structuralState by vm.structuralUiState.collectAsStateWithLifecycle()
    val realtimeState by vm.realtimeUiState.collectAsStateWithLifecycle()
    val locale = rememberAppLocaleScope()
    val sampleRateMismatchDialog by vm.sampleRateMismatchDialogState.collectAsStateWithLifecycle()
    val mixdownVm: ProjectMixdownViewModel = hiltViewModel()
    val mixdownByProjectId by mixdownVm.mixdownByProjectIdState.collectAsStateWithLifecycle()
    val projectMixdown = mixdownByProjectId[projectId]
    val isMixdownInProgress = projectMixdown?.isMixing == true
    val dragController = remember { DragController() }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var pendingRecordProjectName by remember(projectId) { mutableStateOf<String?>(null) }
    var scrubbingPlayheadPositionMs by remember { mutableStateOf<Long?>(null) }
    var showMixdownDialog by remember(projectId) { mutableStateOf(false) }

    val microphonePermissionError = stringResource(R.string.error_microphone_permission_required)
    val newProjectName = stringResource(R.string.default_new_project_name)
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pendingProjectName = pendingRecordProjectName
        pendingRecordProjectName = null
        if (granted && pendingProjectName != null) {
            vm.onRecordPressed(projectId, pendingProjectName)
        } else if (!granted) {
            topBarAlertState.show(coroutineScope, microphonePermissionError)
        }
    }

    val importAudioLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
            val source = ContentResolverAudioImportSource(context.contentResolver, uri)
            vm.importAudio(projectId, source, resolveDisplayName(context, uri))
        }
    }

    fun startRecordingIfPermitted(projectName: String) {
        if (structuralState.isRecordingStartup) return
        if (structuralState.recordingTrackId != null) {
            vm.onRecordPressed(projectId, projectName)
        } else if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            vm.onRecordPressed(projectId, projectName)
        } else {
            pendingRecordProjectName = projectName
        }
    }

    LaunchedEffect(pendingRecordProjectName) {
        if (pendingRecordProjectName != null) {
            recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(vm) {
        coroutineScope {
            launch {
                vm.userMessages.collect { message ->
                    topBarAlertState.show(coroutineScope, message.resolve(context))
                }
            }
            launch {
                vm.openProjectRequests.collect { newProjectId ->
                    onOpenProject(newProjectId)
                }
            }
        }
    }

    sampleRateMismatchDialog?.let { dialog ->
        SampleRateMismatchImportDialog(
            dialog = dialog,
            onImportWithResampling = { vm.confirmImportWithResampling(projectId) },
            onCreateProject = { vm.confirmCreateProjectForImport() },
            onCancel = vm::cancelSampleRateMismatchImport,
        )
    }

    if (showMixdownDialog) {
        AlertDialog(
            onDismissRequest = { showMixdownDialog = false },
            containerColor = AppColors.Bg,
            tonalElevation = 0.dp,
            title = {
                locale.Provide {
                    Text(
                        text = stringResource(R.string.project_mixdown_confirm_title),
                        style = AppText.TileTitle,
                        color = AppColors.Line,
                    )
                }
            },
            text = {
                locale.Provide {
                    Text(
                        text = stringResource(R.string.project_mixdown_confirm_message),
                        style = AppText.TileSubtitle,
                        color = AppColors.Line,
                    )
                }
            },
            dismissButton = {
                locale.Provide {
                    TextButton(onClick = { showMixdownDialog = false }) {
                        Text(
                            text = stringResource(R.string.action_cancel),
                            style = AppText.TileSubtitle,
                            color = AppColors.Line,
                        )
                    }
                }
            },
            confirmButton = {
                locale.Provide {
                    TextButton(onClick = {
                        Log.d(MixConfirmNavTag, "dialog_confirm_clicked")
                        showMixdownDialog = false
                        Log.d(MixConfirmNavTag, "calling_parent_callback")
                        onConfirmMixdown(projectId, structuralState.selectedTrackIds)
                    }) {
                        Text(
                            text = stringResource(R.string.action_mix),
                            style = AppText.TileSubtitle,
                            color = AppColors.Line,
                        )
                    }
                }
            },
        )
    }

    val reorderActive = dragController.isDragging
    LaunchedEffect(reorderActive) {
        if (reorderActive) scrubbingPlayheadPositionMs = null
    }

    val playheadPositionMs =
        scrubbingPlayheadPositionMs ?: realtimeState.globalPlayheadPositionMs

    Column(modifier = Modifier.fillMaxSize()) {
        TimelinePlayheadScrubberPanel(
            playheadPositionMs = playheadPositionMs,
            timelineDurationMs = realtimeState.timelineVisibleDurationMs,
            masterPeakDbText = realtimeState.masterPeakDbText,
            masterPeakIndicatorLevel = realtimeState.masterPeakIndicatorLevel,
            onMasterPeakIndicatorClick = { vm.onMasterPeakIndicatorClicked() },
            onPlayheadScrubStarted = { vm.onPlayheadScrubStarted() },
            onPlayheadScrubCancelled = {
                scrubbingPlayheadPositionMs = null
                vm.onPlayheadScrubCancelled()
            },
            onPlayheadPositionPreview = { positionMs ->
                scrubbingPlayheadPositionMs = positionMs
                vm.onPlayheadScrubPreviewPosition(positionMs, realtimeState.timelineVisibleDurationMs)
            },
            onPlayheadPositionCommit = { positionMs ->
                scrubbingPlayheadPositionMs = null
                vm.onPlayheadScrubCommittedPosition(positionMs, realtimeState.timelineVisibleDurationMs)
            },
            inputLocked =
                reorderActive ||
                structuralState.recordingTrackId != null ||
                structuralState.isRecordingStartup,
        )

        ProjectHeavyWorkspace(
            state = structuralState,
            projectId = projectId,
            showWaveforms = showWaveforms,
            dragController = dragController,
            realtimeUiState = vm.realtimeUiState,
            onTrackPagingSummaryChange = onTrackPagingSummaryChange,
            onEditTrack = onEditTrack,
            vm = vm,
            modifier = Modifier.weight(1f),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Dimens.TileInnerPadding, vertical = Dimens.PanelPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start,
        ) {
            TransportPanel(
                isRecording = structuralState.recordingTrackId != null || structuralState.isRecordingStartup,
                isPlaying = structuralState.isTransportPlaying,
                isPlayEnabled = structuralState.isPlayEnabled,
                isStopEnabled = structuralState.isStopEnabled,
                stopButtonShowsPause = structuralState.stopButtonShowsPause,
                playheadTimeLabel = formatTimelineDuration(playheadPositionMs),
                onPlay = { vm.onPlayPressed() },
                onStop = { vm.onStopPressed() },
                onRecord = { startRecordingIfPermitted(newProjectName) },
                isRecordEnabled = !structuralState.isImportInProgress,
                inputLocked = reorderActive,
                modifier = Modifier.fillMaxWidth(Dimens.TransportPanelWidthFraction),
            )

            Spacer(Modifier.weight(1f))

            ImportAudioButton(
                enabled =
                    structuralState.recordingTrackId == null &&
                    !structuralState.isRecordingStartup &&
                    !structuralState.isImportInProgress &&
                    !isMixdownInProgress,
                onClick = { importAudioLauncher.launch(IMPORT_AUDIO_MIME_TYPES) },
                inputLocked = reorderActive,
            )

            Spacer(Modifier.width(Dimens.Gap / 2))

            MixdownAudioButton(
                enabled =
                    structuralState.isPlayEnabled &&
                    structuralState.recordingTrackId == null &&
                    !structuralState.isRecordingStartup &&
                    !structuralState.isImportInProgress &&
                    !isMixdownInProgress,
                onClick = { showMixdownDialog = true },
                inputLocked = reorderActive,
            )
        }
    }
}

@Composable
private fun ProjectHeavyWorkspace(
    state: ProjectUiState,
    projectId: String,
    showWaveforms: Boolean,
    dragController: DragController,
    realtimeUiState: kotlinx.coroutines.flow.StateFlow<ProjectRealtimeUiState>,
    onTrackPagingSummaryChange: (String) -> Unit,
    onEditTrack: (String) -> Unit,
    vm: ProjectViewModel,
    modifier: Modifier = Modifier,
) {
    SideEffect {
        WaveformRecompositionDiagnostics.logHeavyWorkspaceRecomposition(
            projectId,
            state.waveformStatesByTrackId,
        )
    }

    LaunchedEffect(projectId, showWaveforms, state.tracks.size, state.waveformStatesByTrackId) {
        WaveformRecompositionDiagnostics.logHeavyWorkspaceLaunchedEffect(
            projectId = projectId,
            trigger = "keys(projectId,showWaveforms,tracks.size,waveformStatesByTrackId)",
            waveformStates = state.waveformStatesByTrackId,
        )
        ProjectDiagnostics.logHeavyWorkspaceRendered(
            projectId,
            state.tracks.size,
            showWaveforms,
        )
        ProjectDiagnostics.logTrackWaveformStates(
            projectId,
            state.waveformStatesByTrackId,
            state.tracks.map { it.id },
        )
    }

    ProjectTrackList(
        tracks = state.tracks,
        selectedTrackIds = state.selectedTrackIds,
        recordingTrackId = state.recordingTrackId,
        recordTargetTrackId = state.recordTargetTrackId,
        sessionTrackIds = state.sessionTrackIds,
        importInProgress = state.isImportInProgress,
        realtimeUiState = realtimeUiState,
        timelineClipsByTrackId = state.timelineClipsByTrackId,
        timelineLaneLayoutDurationMs = state.timelineLaneLayoutDurationMs,
        playbackActive = state.playbackSessionActive,
        showWaveforms = showWaveforms,
        dragController = dragController,
        onToggleSelect = vm::toggleSelect,
        onDeleteTrack = vm::deleteTrack,
        onCancelImport = vm::cancelImport,
        onGainChange = vm::setTrackGain,
        onGainCommit = vm::commitTrackGain,
        onPanChange = vm::setTrackPan,
        onPanCommit = vm::commitTrackPan,
        onRenameTrack = vm::renameTrack,
        onToggleRecordTarget = vm::toggleRecordTarget,
        onToggleLoop = vm::toggleTrackLoop,
        onUpdateTrackLoopRegion = vm::updateTrackLoopRegion,
        onEditTrack = onEditTrack,
        onReorderTracks = { vm.setTrackOrderSession(projectId, it) },
        onPersistTrackOrder = { vm.persistTrackOrderToDb(projectId) },
        onTrackPagingSummaryChange = onTrackPagingSummaryChange,
        modifier = modifier,
    )
}

private val IMPORT_AUDIO_MIME_TYPES = arrayOf(
    "audio/wav",
    "audio/x-wav",
    "audio/vnd.wave",
    "audio/wave",
    "audio/mpeg",
    "audio/mp3",
)

private const val MixConfirmNavTag = "MixConfirmNav"
