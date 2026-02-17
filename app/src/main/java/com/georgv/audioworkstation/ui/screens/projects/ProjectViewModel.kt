package com.georgv.audioworkstation.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ProjectUiState(
    val projectId: String? = null,
    val project: ProjectEntity? = null,
    val tracks: List<TrackEntity> = emptyList(),
    val selectedTrackIds: Set<String> = emptySet(),
    val recordingTrackId: String? = null
)


@HiltViewModel
class ProjectViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {

    private val _projectId = MutableStateFlow<String?>(null)
    private val selectedTrackIds = MutableStateFlow<Set<String>>(emptySet())
    private val recordingTrackId = MutableStateFlow<String?>(null)

    val uiState: StateFlow<ProjectUiState> =
        _projectId
            .filterNotNull()
            .flatMapLatest { projectId ->
                combine(
                    repo.observeProjects().map { it.firstOrNull { p -> p.id == projectId } },
                    repo.observeTracks(projectId),
                    selectedTrackIds,
                    recordingTrackId
                ) { project, tracks, selectedId, recordingId ->
                    ProjectUiState(
                        projectId = projectId,
                        project = project,
                        tracks = tracks,
                        selectedTrackIds = selectedId,
                        recordingTrackId = recordingId
                    )
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProjectUiState())

    fun bind(projectId: String) {
        _projectId.value = projectId
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
            val finalName = name ?: run {
                val existing = repo.getProjectWithTracks(projectId)?.tracks?.size ?: 0
                "Take ${existing + 1}"
            }

            val track = TrackEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                name = finalName,
                wavFilePath = ""
            )
            repo.upsertTracks(listOf(track))
        }
    }

    fun toggleSelect(trackId: String) {
        val cur = selectedTrackIds.value
        selectedTrackIds.value =
            if (cur.contains(trackId)) cur - trackId else cur + trackId
    }

    fun onRecordPressed(projectId: String) {
        viewModelScope.launch {
            val selectedIds = selectedTrackIds.value
            if (recordingTrackId.value != null) {
                recordingTrackId.value = null
                return@launch
            }
            // TODO later: playbackEngine.play(selectedIds)  // overdub source tracks

            val existing = repo.getProjectWithTracks(projectId)?.tracks?.size ?: 0
            val newTrack = TrackEntity(
                id = UUID.randomUUID().toString(),
                projectId = projectId,
                name = "Take ${existing + 1}",
                wavFilePath = ""
            )
            repo.upsertTracks(listOf(newTrack))

            recordingTrackId.value = newTrack.id

        }
    }

    fun onStopPressed() {
        recordingTrackId.value = null
    }



    fun deleteProject() {
        val id = _projectId.value ?: return
        viewModelScope.launch {
            repo.deleteProject(id)
        }
    }
}
