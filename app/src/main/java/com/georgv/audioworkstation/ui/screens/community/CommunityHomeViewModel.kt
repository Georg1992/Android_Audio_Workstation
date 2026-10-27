package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
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
)

@HiltViewModel
class CommunityHomeViewModel @Inject constructor(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val sharedProjects: SharedProjects,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val projects = MutableStateFlow<List<SharedProject>>(emptyList())
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    init {
        viewModelScope.launch {
            sessions.state.collect { session ->
                if (session == null) {
                    projects.value = emptyList()
                    return@collect
                }
                try {
                    projects.value = sharedProjects.list()
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    logWarning(TAG, "shared projects failed", error)
                    messages.send(UiMessage(R.string.error_shared_projects_failed))
                }
            }
        }
    }

    val uiState: StateFlow<CommunityHomeUiState> =
        combine(sessions.state, busy, projects) { session, isBusy, shared ->
            CommunityHomeUiState(
                sessionKnown = true,
                email = session?.email,
                name = session?.name,
                projects = shared,
                busy = isBusy,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            CommunityHomeUiState(),
        )

    fun signOut() {
        submit {
            val session = sessions.current() ?: return@submit
            accounts.signOut(session.refreshToken)
            sessions.clear()
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

    private companion object {
        const val TAG = "CommunityHomeViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
