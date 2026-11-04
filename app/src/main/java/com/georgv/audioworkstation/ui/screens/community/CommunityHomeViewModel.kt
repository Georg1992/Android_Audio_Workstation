package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.ui.reportedFailure
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.signOutLocallyThenRevoke
import com.georgv.audioworkstation.online.share.SharedProject
import com.georgv.audioworkstation.online.share.SharedProjects
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CommunityHomeUiState(
    val sessionKnown: Boolean = false,
    val email: String? = null,
    val name: String? = null,
    val projects: List<SharedProject> = emptyList(),
    val busy: Boolean = false,
    val preparing: Boolean = false,
)

@HiltViewModel
class CommunityHomeViewModel @Inject constructor(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val sharedProjects: SharedProjects,
    private val localProjects: LocalProjectLookup,
    private val ownedShares: OwnedShareRefresh,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val preparing = MutableStateFlow(false)
    private val projects = MutableStateFlow<List<SharedProject>>(emptyList())
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    private val opens = Channel<String>(capacity = Channel.BUFFERED)

    val userMessages = messages.receiveAsFlow()
    val openProject = opens.receiveAsFlow()

    init {
        viewModelScope.launch {
            sessions.state.collect {
                try {
                    load()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    report(reportedFailure(error, R.string.error_shared_projects_failed), error)
                }
            }
        }
    }

    val uiState: StateFlow<CommunityHomeUiState> =
        combine(
            sessions.state,
            busy,
            projects,
            preparing,
        ) { session, isBusy, shared, isPreparing ->
            CommunityHomeUiState(
                sessionKnown = true,
                email = session?.email,
                name = session?.name,
                projects = shared,
                busy = isBusy,
                preparing = isPreparing,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            CommunityHomeUiState(),
        )

    fun reload() {
        viewModelScope.launch {
            try {
                load()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(reportedFailure(error, R.string.error_shared_projects_failed), error)
            }
        }
    }

    fun open(projectId: String) {
        viewModelScope.launch {
            try {
                val title = projects.value.firstOrNull { it.id == projectId }?.title.orEmpty()
                localProjects.present(projectId, title)
                opens.send(projectId)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                report(reportedFailure(error, R.string.error_card_not_on_phone), error)
            }
        }
    }

    fun signOut() {
        submit {
            signOutLocallyThenRevoke(sessions, accounts) { error ->
                logWarning(TAG, "token revoke failed", error)
            }
        }
    }

    private suspend fun load() {
        if (sessions.current() == null) {
            projects.value = emptyList()
            return
        }
        preparing.value = true
        try {
            val publishError = publishCards()
            val listed = sharedProjects.list()
            val removed = ownedShares.unpublishAbsent(listed)
            projects.value = listed.filter { project -> project.id !in removed }
            if (publishError != null && publishError.message != MissingShareRoute) {
                throw publishError
            }
            if (publishError != null) {
                logWarning(TAG, "shared card was not published", publishError)
            }
        } finally {
            preparing.value = false
        }
    }

    private suspend fun publishCards(): Exception? {
        try {
            ownedShares.publishOwnerNames()
            ownedShares.publishMixes()
            return null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            return error
        }
    }

    private fun submit(block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "account request failed", error)
                messages.send(UiMessage(R.string.error_sign_out_failed))
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun report(message: Int, error: Exception) {
        logWarning(TAG, "community card failed", error)
        messages.send(UiMessage(message))
    }

    private companion object {
        const val TAG = "CommunityHomeViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val MissingShareRoute = "share api status 404"
    }
}
