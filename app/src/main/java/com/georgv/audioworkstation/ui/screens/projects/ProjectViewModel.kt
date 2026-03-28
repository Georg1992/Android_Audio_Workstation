package com.georgv.audioworkstation.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
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
    private val repo: ProjectRepository
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)
    private var boundProjectId: String? = null

    private val playingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val recordingTrackId = MutableStateFlow<String?>(null)
    private val messages = Channel<String>(capacity = Channel.BUFFERED)

    private val tracksSession = MutableStateFlow<List<TrackEntity>>(emptyList())
    private val project = projectId
        .flatMapLatest { id ->
            if (id == null) flowOf(null) else repo.observeProject(id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val userMessages = messages.receiveAsFlow()

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
            tracksSession,
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
        if (boundProjectId == projectId) return
        boundProjectId = projectId
        this.projectId.value = projectId
        val tracks = runCatching {
            repo.observeTracks(projectId).first()
        }.getOrElse {
            tracksSession.value = emptyList()
            showMessage("Failed to load project tracks.")
            return
        }
        tracksSession.value = tracks
    }

    suspend fun ensureProjectExists(projectId: String, name: String): Boolean {
        return runDbAction("Failed to create project.") {
            if (!repo.projectExists(projectId)) {
                repo.upsertProject(ProjectEntity(id = projectId, name = name))
            }
        }
    }

    fun deleteProject() {
        val id = projectId.value ?: return
        viewModelScope.launch {
            runDbAction("Failed to delete project.") {
                repo.deleteProject(id)
            }
        }
    }

    fun addTrack(projectId: String, name: String? = null) {
        if (this.projectId.value != projectId) return
        viewModelScope.launch {
            if (!ensureProjectExists(projectId, "New Project")) return@launch
            runDbAction("Failed to add track.") {
                val existingCount = tracksSession.value.size
                val finalName = name ?: "Take ${existingCount + 1}"

                val track = TrackEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    position = existingCount,
                    name = finalName,
                    wavFilePath = ""
                )
                repo.upsertTracks(listOf(track))
                tracksSession.value = tracksSession.value + track
            }
        }
    }

    fun deleteTrack(trackId: String) {
        val previousSelected = selectedTrackIds.value
        val previousPlaying = playingTrackIds.value
        val previousRecording = recordingTrackId.value
        val previousTracks = tracksSession.value

        selectedTrackIds.value = selectedTrackIds.value - trackId
        if (recordingTrackId.value == trackId) recordingTrackId.value = null
        if (playingTrackIds.value.contains(trackId)) {
            playingTrackIds.value = playingTrackIds.value - trackId
        }
        val newList = previousTracks
            .filter { it.id != trackId }
            .mapIndexed { i, t -> t.copy(position = i) }
        tracksSession.value = newList
        viewModelScope.launch {
            runDbActionWithRollback(
                errorMessage = "Failed to delete track.",
                rollback = {
                    selectedTrackIds.value = previousSelected
                    playingTrackIds.value = previousPlaying
                    recordingTrackId.value = previousRecording
                    tracksSession.value = previousTracks
                }
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
        val previousTracks = tracksSession.value
        val currentTrack = previousTracks.find { it.id == trackId } ?: return
        val normalizedName = when (val validation = validateTrackName(newName)) {
            is TrackNameValidationResult.Invalid -> {
                tryShowMessage(validation.message)
                return
            }
            is TrackNameValidationResult.Valid -> validation.normalizedName
        }
        if (normalizedName == (currentTrack.name ?: "").trim()) return

        val updatedTrack = currentTrack.copy(name = normalizedName)
        tracksSession.value = previousTracks.map { track ->
            if (track.id == trackId) updatedTrack else track
        }
        viewModelScope.launch {
            runDbActionWithRollback(
                errorMessage = "Failed to rename track.",
                rollback = { tracksSession.value = previousTracks }
            ) {
                repo.upsertTrack(updatedTrack)
            }
        }
    }

    fun setTrackOrderSession(projectId: String, orderedTracks: List<TrackEntity>) {
        if (this.projectId.value != projectId) return
        if (orderedTracks.isEmpty()) return
        val sessionById = tracksSession.value.associateBy { it.id }
        val merged = orderedTracks
            .filter { it.id in sessionById }
            .mapIndexed { index, row -> sessionById.getValue(row.id).copy(position = index) }
        if (merged.isEmpty()) return
        val presentIds = merged.map { it.id }.toSet()
        val trailing = tracksSession.value
            .filter { it.id !in presentIds }
            .sortedBy { it.position }
            .mapIndexed { i, t -> t.copy(position = merged.size + i) }
        val next = merged + trailing
        if (next.map { it.id } == tracksSession.value.map { it.id }) return
        tracksSession.value = next
    }

    fun persistTrackOrderToDb(projectId: String) {
        if (this.projectId.value != projectId) return
        val list = tracksSession.value
        if (list.isEmpty()) return
        viewModelScope.launch {
            runDbAction("Failed to save track order.") {
                repo.updateTracks(list)
            }
        }
    }

    fun toggleSelect(trackId: String) {
        val cur = selectedTrackIds.value
        selectedTrackIds.value = if (cur.contains(trackId)) cur - trackId else cur + trackId
    }

    fun onRecordPressed(projectId: String) {
        viewModelScope.launch {
            if (recordingTrackId.value != null) {
                recordingTrackId.value = null
                return@launch
            }

            if (!ensureProjectExists(projectId, "New Project")) return@launch
            runDbAction("Failed to create recording track.") {
                val existingCount = tracksSession.value.size
                val newTrack = TrackEntity(
                    id = UUID.randomUUID().toString(),
                    projectId = projectId,
                    position = existingCount,
                    name = "Take ${existingCount + 1}",
                    wavFilePath = ""
                )
                repo.upsertTracks(listOf(newTrack))
                tracksSession.value = tracksSession.value + newTrack
                recordingTrackId.value = newTrack.id
            }
        }
    }

    fun onPlayPressed() {
        val selected = selectedTrackIds.value

        viewModelScope.launch {
            if (playingTrackIds.value.isNotEmpty()) {
                playingTrackIds.value = emptySet()
                return@launch
            }

            if (selected.isEmpty()) return@launch
            playingTrackIds.value = selected
        }
    }

    fun onStopPressed() {
        recordingTrackId.value = null
        playingTrackIds.value = emptySet()
    }
}
