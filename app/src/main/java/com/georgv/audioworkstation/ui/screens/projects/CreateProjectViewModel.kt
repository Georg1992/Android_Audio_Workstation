package com.georgv.audioworkstation.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class CreateProjectUiState(
    val projectName: String = "",
    val isSaving: Boolean = false
)

@HiltViewModel
class CreateProjectViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateProjectUiState())
    val uiState: StateFlow<CreateProjectUiState> = _uiState.asStateFlow()

    private val messages = Channel<String>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    private val createdProjectIds = Channel<String>(capacity = Channel.BUFFERED)
    val createdProjects = createdProjectIds.receiveAsFlow()

    fun onProjectNameChange(value: String) {
        _uiState.value = _uiState.value.copy(projectName = value)
    }

    fun createProject() {
        if (_uiState.value.isSaving) return

        val normalizedName = _uiState.value.projectName.trim()
        if (normalizedName.isEmpty()) {
            messages.trySend("Project name cannot be blank.")
            return
        }

        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val projectId = UUID.randomUUID().toString()
            runCatching {
                repo.upsertProject(ProjectEntity(id = projectId, name = normalizedName))
            }.onSuccess {
                createdProjectIds.send(projectId)
            }.onFailure {
                messages.send("Failed to create project.")
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }
}
