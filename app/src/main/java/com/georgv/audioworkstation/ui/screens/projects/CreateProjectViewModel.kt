package com.georgv.audioworkstation.ui.screens.projects

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.ProjectSampleRate
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.validation.NameValidationResult
import com.georgv.audioworkstation.core.validation.toProjectNameUiMessage
import com.georgv.audioworkstation.core.validation.validateName
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
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
    val sampleRate: ProjectSampleRate = ProjectSampleRate.Default,
    val isSaving: Boolean = false
)

@HiltViewModel
class CreateProjectViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CreateProjectUiState())
    val uiState: StateFlow<CreateProjectUiState> = _uiState.asStateFlow()

    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    private val createdProjectIds = Channel<String>(capacity = Channel.BUFFERED)
    val createdProjects = createdProjectIds.receiveAsFlow()

    fun onProjectNameChange(value: String) {
        _uiState.value = _uiState.value.copy(projectName = value)
    }

    fun onSampleRateChange(sampleRate: ProjectSampleRate) {
        if (_uiState.value.isSaving) return
        _uiState.value = _uiState.value.copy(sampleRate = sampleRate)
    }

    fun createProject() {
        if (_uiState.value.isSaving) return

        val normalizedName = when (val validation = validateName(_uiState.value.projectName)) {
            is NameValidationResult.Invalid -> {
                messages.trySend(validation.error.toProjectNameUiMessage())
                return
            }
            is NameValidationResult.Valid -> validation.normalized
        }

        val selectedSampleRate = _uiState.value.sampleRate
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true)
            val projectId = UUID.randomUUID().toString()
            try {
                repo.upsertProject(
                    ProjectEntity(
                        id = projectId,
                        name = normalizedName,
                        sampleRate = selectedSampleRate.hz
                    )
                )
                createdProjectIds.send(projectId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (db: RuntimeException) {
                // Room/SQLite raises RuntimeException subclasses for constraint and disk failures.
                messages.send(UiMessage(R.string.error_create_project_failed))
            }
            _uiState.value = _uiState.value.copy(isSaving = false)
        }
    }
}
