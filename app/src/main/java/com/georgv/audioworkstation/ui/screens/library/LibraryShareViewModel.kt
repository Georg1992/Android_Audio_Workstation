package com.georgv.audioworkstation.ui.screens.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.ui.reportedFailure
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.share.ProjectShare
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class LibraryShareViewModel @Inject constructor(
    sessions: AccountSessionStore,
    private val projectShare: ProjectShare,
) : ViewModel() {
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    /** The library list itself does not change with the account. Share is only offered while signed in. */
    val shareEnabled: StateFlow<Boolean> =
        sessions.state
            .map { session -> session != null }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val sharingIds = MutableStateFlow<Set<String>>(emptySet())
    val sharingProjectIds: StateFlow<Set<String>> = sharingIds

    fun shareProject(projectId: String) {
        runWhileSharing(projectId, R.string.library_share_done, R.string.error_share_project_failed) {
            projectShare.share(projectId)
        }
    }

    fun unshareProject(projectId: String) {
        runWhileSharing(projectId, R.string.library_unshare_done, R.string.error_unshare_project_failed) {
            projectShare.unshare(projectId)
        }
    }

    private fun runWhileSharing(
        projectId: String,
        doneMessage: Int,
        failedMessage: Int,
        work: suspend () -> Unit,
    ) {
        var started = false
        sharingIds.update { current ->
            if (projectId in current) {
                current
            } else {
                started = true
                current + projectId
            }
        }
        if (!started) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                work()
                messages.send(UiMessage(doneMessage))
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "share action failed: $projectId", error)
                messages.send(UiMessage(reportedFailure(error, failedMessage)))
            } finally {
                sharingIds.update { current -> current - projectId }
            }
        }
    }

    private companion object {
        const val TAG = "LibraryShareViewModel"
    }
}
