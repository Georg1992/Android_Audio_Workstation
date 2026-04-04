package com.georgv.audioworkstation.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.toPlaybackSpec
import com.georgv.audioworkstation.core.audio.toRecordingSpec
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.max
import javax.inject.Inject

data class ProjectUiState(
    val projectId: String? = null,
    val project: ProjectEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val selectedTrackIds: Set<String> = emptySet(),
    val playingTrackIds: Set<String> = emptySet(),
    val recordingTrackId: String? = null
) {
    val isPlayEnabled: Boolean
        get() = selectedTrackIds.isNotEmpty()

    val isStopEnabled: Boolean
        get() = recordingTrackId != null || playingTrackIds.isNotEmpty()
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repo: ProjectRepository,
    private val audioController: AudioController
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)

    private val playingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val recordingTrackId = MutableStateFlow<String?>(null)
    private val messages = Channel<String>(capacity = Channel.BUFFERED)
    private val sessionTrackOrder = MutableStateFlow<List<String>?>(null)
    private var playbackMonitorJob: Job? = null

    private val project = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.observeProject(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    private val projectTracks = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(emptyList()) else repo.observeTracks(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val userMessages = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            combine(projectTracks, sessionTrackOrder) { tracks, orderedTrackIds ->
                tracks.map { it.id } to orderedTrackIds
            }.collect { (dbTrackIds, orderedTrackIds) ->
                if (!orderedTrackIds.isNullOrEmpty() && orderedTrackIds == dbTrackIds) {
                    sessionTrackOrder.value = null
                }
            }
        }
    }

    private suspend fun showMessage(message: String) {
        messages.send(message)
    }

    private fun tryShowMessage(message: String) {
        messages.trySend(message)
    }

    private suspend inline fun runDbAction(
        errorMessage: String,
        crossinline action: suspend () -> Unit
    ): Boolean {
        return runCatching {
            action()
        }.onFailure {
            showMessage(errorMessage)
        }.isSuccess
    }

    private suspend inline fun runDbActionWithRollback(
        errorMessage: String,
        rollback: () -> Unit,
        crossinline action: suspend () -> Unit
    ) {
        runCatching {
            action()
        }.onFailure {
            rollback()
            showMessage(errorMessage)
        }
    }

    val uiState: StateFlow<ProjectUiState> =
        combine(
            combine(projectId, project) { pid, project -> pid to project },
            combine(projectTracks, sessionTrackOrder) { tracks, orderedTrackIds ->
                applySessionTrackOrder(tracks, orderedTrackIds)
            },
            selectedTrackIds,
            playingTrackIds,
            recordingTrackId
        ) { pidProject, tracks, selected, playing, recording ->
            val (pid, project) = pidProject
            ProjectUiState(
                projectId = pid,
                project = project,
                tracks = tracks,
                selectedTrackIds = selected,
                playingTrackIds = playing,
                recordingTrackId = recording
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    suspend fun bind(projectId: String) {
        if (this.projectId.value != projectId) {
            playbackMonitorJob?.cancel()
            sessionTrackOrder.value = null
            selectedTrackIds.value = emptySet()
            playingTrackIds.value = emptySet()
            recordingTrackId.value = null
        }
        this.projectId.value = projectId
    }

    suspend fun ensureProjectExists(projectId: String, name: String): Boolean {
        return runDbAction("Failed to create project.") {
            if (!repo.projectExists(projectId)) {
                repo.upsertProject(ProjectEntity(id = projectId, name = name))
            }
        }
    }

    private suspend fun currentProjectForAudio(projectId: String): ProjectEntity? {
        val loadedProject = project.value?.takeIf { it.id == projectId } ?: repo.observeProject(projectId).first()
        if (loadedProject == null) {
            showMessage("Project audio settings are unavailable.")
        }
        return loadedProject
    }

    private fun createDefaultTrack(projectId: String, existingCount: Int): TrackEntity =
        TrackEntity(
            id = UUID.randomUUID().toString(),
            projectId = projectId,
            position = existingCount,
            name = "Take ${existingCount + 1}",
            wavFilePath = ""
        )

    fun deleteProject() {
        val id = projectId.value ?: return
        viewModelScope.launch {
            runDbAction("Failed to delete project.") {
                repo.deleteProject(id)
            }
        }
    }

    fun renameProject(newName: String) {
        val currentProject = uiState.value.project ?: return
        val normalizedName = when (val validation = validateProjectName(newName)) {
            is ProjectNameValidationResult.Invalid -> {
                tryShowMessage(validation.message)
                return
            }
            is ProjectNameValidationResult.Valid -> validation.normalizedName
        }
        if (normalizedName == (currentProject.name ?: "").trim()) return

        val updatedProject = currentProject.copy(name = normalizedName)
        viewModelScope.launch {
            runDbAction("Failed to rename project.") {
                repo.upsertProject(updatedProject)
            }
        }
    }

    fun addTrack(projectId: String, name: String? = null) {
        if (this.projectId.value != projectId) return
        viewModelScope.launch {
            if (!ensureProjectExists(projectId, "New Project")) return@launch
            runDbAction("Failed to add track.") {
                val existingCount = uiState.value.tracks.size
                val track = createDefaultTrack(projectId, existingCount)
                    .copy(name = name ?: "Take ${existingCount + 1}")
                repo.upsertTracks(listOf(track))
            }
        }
    }

    fun deleteTrack(trackId: String) {
        if (recordingTrackId.value == trackId) {
            tryShowMessage("Stop recording before deleting this track.")
            return
        }
        if (playingTrackIds.value.contains(trackId)) {
            tryShowMessage("Stop playback before deleting this track.")
            return
        }

        val previousSelected = selectedTrackIds.value
        selectedTrackIds.value = selectedTrackIds.value - trackId
        val newList = uiState.value.tracks
            .filter { it.id != trackId }
            .mapIndexed { i, t -> t.copy(position = i) }
        viewModelScope.launch {
            runDbActionWithRollback(
                errorMessage = "Failed to delete track.",
                rollback = { selectedTrackIds.value = previousSelected }
            ) {
                if (newList.isEmpty()) {
                    repo.deleteTrack(trackId)
                } else {
                    repo.deleteTrackAndUpdatePositions(trackId, newList)
                }
            }
        }
    }

    fun renameTrack(trackId: String, newName: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val normalizedName = when (val validation = validateTrackName(newName)) {
            is TrackNameValidationResult.Invalid -> {
                tryShowMessage(validation.message)
                return
            }
            is TrackNameValidationResult.Valid -> validation.normalizedName
        }
        if (normalizedName == (currentTrack.name ?: "").trim()) return

        val updatedTrack = currentTrack.copy(name = normalizedName)
        viewModelScope.launch {
            runDbAction("Failed to rename track.") {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun setTrackGain(trackId: String, gain: Float) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        if (gain == currentTrack.gain) return

        val updatedTrack = currentTrack.copy(gain = gain)
        if (playingTrackIds.value.contains(trackId)) {
            audioController.setPlaybackGain(gain / 100f)
        }
        viewModelScope.launch {
            runDbAction("Failed to update track gain.") {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun setTrackOrderSession(projectId: String, orderedTracks: List<TrackEntity>) {
        if (this.projectId.value != projectId) return
        if (orderedTracks.isEmpty()) return
        val sessionById = uiState.value.tracks.associateBy { it.id }
        val merged = orderedTracks
            .filter { it.id in sessionById }
            .mapIndexed { index, row -> sessionById.getValue(row.id).copy(position = index) }
        if (merged.isEmpty()) return
        val presentIds = merged.map { it.id }.toSet()
        val trailing = uiState.value.tracks
            .filter { it.id !in presentIds }
            .sortedBy { it.position }
            .mapIndexed { i, t -> t.copy(position = merged.size + i) }
        val next = merged + trailing
        if (next.map { it.id } == uiState.value.tracks.map { it.id }) return
        sessionTrackOrder.value = next.map { it.id }
    }

    fun persistTrackOrderToDb(projectId: String) {
        if (this.projectId.value != projectId) return
        val list = uiState.value.tracks.mapIndexed { index, track -> track.copy(position = index) }
        if (list.isEmpty()) return
        viewModelScope.launch {
            runDbActionWithRollback(
                errorMessage = "Failed to save track order.",
                rollback = { sessionTrackOrder.value = null }
            ) {
                repo.updateTracks(list)
            }
        }
    }

    fun toggleSelect(trackId: String) {
        val cur = selectedTrackIds.value
        selectedTrackIds.value = if (cur.contains(trackId)) cur - trackId else cur + trackId
    }

    fun onRecordPressed(projectId: String, projectName: String = "New Project") {
        viewModelScope.launch {
            if (recordingTrackId.value != null) {
                showMessage("Stop recording before starting a new take.")
                return@launch
            }

            if (!ensureProjectExists(projectId, projectName)) return@launch
            val currentProject = currentProjectForAudio(projectId) ?: return@launch

            val existingCount = uiState.value.tracks.size
            val pendingTrack = createDefaultTrack(projectId, existingCount)
            val outputPath = audioController.startRecording(currentProject.toRecordingSpec(pendingTrack))
            if (outputPath == null) {
                showMessage("Failed to start recording.")
                return@launch
            }
            val newTrack = pendingTrack.copy(
                wavFilePath = outputPath,
                timeStampStart = System.currentTimeMillis(),
                isRecording = true
            )

            runDbActionWithRollback(
                errorMessage = "Failed to create recording track.",
                rollback = { audioController.stopRecording() }
            ) {
                repo.upsertTracks(listOf(newTrack))
                recordingTrackId.value = newTrack.id
            }
        }
    }

    fun onPlayPressed() {
        val selected = selectedTrackIds.value

        viewModelScope.launch {
            if (playingTrackIds.value.isNotEmpty()) {
                showMessage("Stop playback before starting playback again.")
                return@launch
            }

            if (selected.isEmpty()) return@launch
            val currentProjectId = projectId.value ?: return@launch
            val currentProject = currentProjectForAudio(currentProjectId) ?: return@launch
            val selectedTracks = uiState.value.tracks.filter { it.id in selected }
            if (selectedTracks.size != 1) {
                showMessage("Playback currently supports one selected track at a time.")
                return@launch
            }
            val selectedTrack = selectedTracks.single()
            val playbackSpec = currentProject.toPlaybackSpec(selectedTrack)
            if (playbackSpec == null) {
                showMessage("Selected tracks have no audio yet.")
                return@launch
            }
            if (!audioController.startPlayback(playbackSpec)) {
                showMessage("Failed to start playback.")
                return@launch
            }
            playingTrackIds.value = setOf(selectedTrack.id)
            startPlaybackMonitor(selectedTrack.id)
        }
    }

    fun onStopPressed() {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = null
        val activeRecordingTrackId = recordingTrackId.value
        if (activeRecordingTrackId != null && audioController.stopRecording()) {
            finalizeRecordingTrack(activeRecordingTrackId)
        }
        if (playingTrackIds.value.isNotEmpty()) {
            audioController.stopPlayback()
        }
        recordingTrackId.value = null
        playingTrackIds.value = emptySet()
    }

    override fun onCleared() {
        onStopPressed()
        super.onCleared()
    }

    private fun applySessionTrackOrder(
        tracks: List<TrackEntity>,
        orderedTrackIds: List<String>?
    ): List<TrackEntity> {
        if (orderedTrackIds.isNullOrEmpty()) return tracks

        val trackById = tracks.associateBy { it.id }
        val orderedTracks = orderedTrackIds.mapNotNull(trackById::get)
        if (orderedTracks.isEmpty()) return tracks

        val orderedIds = orderedTrackIds.toSet()
        val trailingTracks = tracks.filter { it.id !in orderedIds }
        return orderedTracks + trailingTracks
    }

    private fun startPlaybackMonitor(trackId: String) {
        playbackMonitorJob?.cancel()
        playbackMonitorJob = viewModelScope.launch {
            while (audioController.isPlaybackActive()) {
                delay(50)
            }
            if (playingTrackIds.value == setOf(trackId)) {
                playingTrackIds.value = emptySet()
            }
            playbackMonitorJob = null
        }
    }

    private fun finalizeRecordingTrack(trackId: String) {
        val currentTrack = uiState.value.tracks.find { it.id == trackId } ?: return
        val stopTimestamp = System.currentTimeMillis()
        val duration = max(0L, stopTimestamp - currentTrack.timeStampStart)
        val finalizedTrack = currentTrack.copy(
            timeStampStop = stopTimestamp,
            duration = duration,
            isRecording = false
        )

        viewModelScope.launch {
            runDbAction("Failed to update recording metadata.") {
                repo.upsertTrack(finalizedTrack)
            }
        }
    }
}
