package com.georgv.audioworkstation.ui.screens.library

import androidx.lifecycle.ViewModel
import com.georgv.audioworkstation.core.util.logWarning
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.DataAvailability
import com.georgv.audioworkstation.core.ui.ScreenState
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.data.repository.ProjectRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val repo: ProjectRepository
) : ViewModel() {

    val uiState: StateFlow<ScreenState<LibraryContent>> =
        repo.projectsState
            .map { projects ->
                ScreenState(
                    availability = DataAvailability.Ready,
                    content = LibraryContent(projects = projects),
                )
            }
            .onEach { LibraryDiagnostics.logStateEmitted(it) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(5_000),
                initialLibraryScreenState(repo),
            )

    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    fun deleteProject(projectId: String) {
        viewModelScope.launch {
            try {
                repo.deleteProject(projectId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "deleteProject failed: $projectId", error)
                messages.send(UiMessage(R.string.error_delete_project_failed))
            }
        }
    }

    /** Prefetch project metadata before Library → Project navigation. */
    fun warmUpProject(projectId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            repo.observeProject(projectId).first()
            repo.observeTracks(projectId).first()
        }
    }

    private companion object {
        const val TAG = "LibraryViewModel"

        fun initialLibraryScreenState(repo: ProjectRepository): ScreenState<LibraryContent> =
            ScreenState(
                availability =
                    if (repo.projectsReady.value) {
                        DataAvailability.Ready
                    } else {
                        DataAvailability.Pending
                    },
                content = LibraryContent(projects = repo.projectsState.value),
            )
    }
}
