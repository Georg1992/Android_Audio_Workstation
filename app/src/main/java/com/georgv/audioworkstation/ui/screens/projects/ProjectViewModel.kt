package com.georgv.audioworkstation.ui.screens.projects


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
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
    val transportState: TransportState
        get() = when {
            recordingTrackId != null && selectedTrackIds.isNotEmpty() -> TransportState.Overdub
            recordingTrackId != null -> TransportState.Recording
            playingTrackIds.isNotEmpty() -> TransportState.Playing
            else -> TransportState.Idle
        }

    val isPlayEnabled: Boolean
        get() = selectedTrackIds.isNotEmpty()

    val isStopEnabled: Boolean
        get() = recordingTrackId != null || playingTrackIds.isNotEmpty()
}


@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {

    private val projectId = MutableStateFlow<String?>(null)

    private val playingTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val recordingTrackId = MutableStateFlow<String?>(null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val uiState: StateFlow<ProjectUiState> =
        projectId
            .filterNotNull()
            .flatMapLatest { id ->
                combine(
                    repo.observeProjects().map { list -> list.firstOrNull { it.id == id } },
                    repo.observeTracks(id),
                    selectedTrackIds,
                    playingTrackIds,
                    recordingTrackId
                ) { project, tracks, selected, playing, recording ->
                    ProjectUiState(
                        projectId = id,
                        project = project,
                        tracks = tracks,
                        selectedTrackIds = selected,
                        playingTrackIds = playing,
                        recordingTrackId = recording
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    fun bind(projectId: String) {
        this.projectId.value = projectId
    }

    fun ensureProjectExists(projectId: String, name: String) {
        viewModelScope.launch {
            if (repo.getProjectWithTracks(projectId) == null) {
                repo.upsertProject(ProjectEntity(id = projectId, name = name))
            }
        }
    }

    fun addTrack(projectId: String, name: String? = null) {
        viewModelScope.launch {
            val existingCount = repo.getProjectWithTracks(projectId)?.tracks?.size ?: 0
            val finalName = name ?: "Take ${existingCount + 1}"

            val track = TrackEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                position = existingCount,
                name = finalName,
                wavFilePath = ""
            )
            repo.upsertTracks(listOf(track))
        }
    }

    fun toggleSelect(trackId: String) {
        val cur = selectedTrackIds.value
        selectedTrackIds.value = if (cur.contains(trackId)) cur - trackId else cur + trackId
    }

    fun onRecordPressed(projectId: String) {
        viewModelScope.launch {
            // stop recording
            if (recordingTrackId.value != null) {
                recordingTrackId.value = null
                return@launch
            }

            val existingCount = repo.getProjectWithTracks(projectId)?.tracks?.size ?: 0
            val newTrack = TrackEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                position = existingCount,
                name = "Take ${existingCount + 1}",
                wavFilePath = ""
            )
            repo.upsertTracks(listOf(newTrack))

            recordingTrackId.value = newTrack.id
        }
    }

    fun onPlayPressed() {
        val selected = selectedTrackIds.value

        viewModelScope.launch {
            // toggle play
            if (playingTrackIds.value.isNotEmpty()) {
                playingTrackIds.value = emptySet()
                return@launch
            }

            if (selected.isEmpty()) return@launch
            playingTrackIds.value = selected
        }
    }

    fun onStopPressed() {
        // stop everything
        recordingTrackId.value = null
        playingTrackIds.value = emptySet()
    }

    fun deleteProject() {
        val id = projectId.value ?: return
        viewModelScope.launch {
            repo.deleteProject(id)
        }
    }

    /**
     * Reorder support (used later by drag & drop).
     */
    fun moveTrack(projectId: String, trackId: String, toIndex: Int) {
        viewModelScope.launch {
            val current = repo.getProjectWithTracks(projectId)?.tracks
                ?.sortedBy { it.position }
                ?.toMutableList()
                ?: return@launch

            val fromIndex = current.indexOfFirst { it.id == trackId }
            if (fromIndex == -1) return@launch

            val moved = current.removeAt(fromIndex)
            val safeIndex = toIndex.coerceIn(0, current.size)
            current.add(safeIndex, moved)

            val updated = current.mapIndexed { index, t -> t.copy(position = index) }
            repo.updateTracks(updated)
        }
    }

    fun deleteTrack(trackId: String) {
        viewModelScope.launch {
            selectedTrackIds.value = selectedTrackIds.value - trackId
            if (recordingTrackId.value == trackId) recordingTrackId.value = null
            if (playingTrackIds.value.contains(trackId)) {
                playingTrackIds.value = playingTrackIds.value - trackId
            }
            repo.deleteTrack(trackId)
        }
    }
}
