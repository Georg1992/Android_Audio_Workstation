package com.georgv.audioworkstation.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.IOException
import javax.inject.Inject

data class LibraryUiState(
    val projects: List<ProjectEntity> = emptyList()
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {

    val uiState: StateFlow<LibraryUiState> = repo.observeProjects()
        .map { projects -> LibraryUiState(projects = projects) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUiState())

    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                repo.deleteProject(projectId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (io: IOException) {
                // File-system removal failed (locked file, missing storage permission, etc.).
                messages.send(UiMessage(R.string.error_delete_project_failed))
            } catch (db: RuntimeException) {
                // Room/SQLite raises RuntimeException subclasses for constraint and disk failures.
                messages.send(UiMessage(R.string.error_delete_project_failed))
            }
        }
    }
}
