package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.AccountApi
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.GmailSignIn
import com.georgv.audioworkstation.online.GmailSignInCancelled
import com.georgv.audioworkstation.online.HttpUnauthorized
import com.georgv.audioworkstation.online.OnlineApiException
import com.georgv.audioworkstation.online.ProjectShareCoordinator
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
import kotlinx.coroutines.withContext

data class CommunityUiState(
    val signedInEmail: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val sessions: AccountSessionStore,
    private val accounts: AccountApi,
    private val gmail: GmailSignIn,
    private val share: ProjectShareCoordinator,
    private val dispatchers: AppDispatchers,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    val uiState: StateFlow<CommunityUiState> =
        combine(sessions.state, busy) { session, isBusy ->
            CommunityUiState(signedInEmail = session?.email, busy = isBusy)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            CommunityUiState(),
        )

    fun signIn(email: String, password: String) {
        submit(email, password, R.string.error_sign_in_failed, accounts::createSession)
    }

    fun register(email: String, password: String, confirmation: String) {
        if (busy.value) return
        val emailText = email.trim()
        if (rejectRegistration(emailText, password, confirmation)) return
        viewModelScope.launch {
            busy.value = true
            var registered = false
            try {
                val session = withContext(dispatchers.io) {
                    accounts.register(emailText, password)
                    registered = true
                    accounts.createSession(emailText, password)
                }
                finishSignIn(session)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "registration failed", error)
                val failure = if (registered) R.string.error_sign_in_failed else R.string.error_create_account_failed
                messages.send(UiMessage(failure))
            } finally {
                busy.value = false
            }
        }
    }

    fun signInWithGmail() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                val idToken = gmail.idToken()
                val session = withContext(dispatchers.io) { accounts.createGoogleSession(idToken) }
                finishSignIn(session)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: GmailSignInCancelled) {
            } catch (error: Exception) {
                logWarning(TAG, "gmail sign-in failed", error)
                messages.send(UiMessage(R.string.error_gmail_sign_in_failed))
            } finally {
                busy.value = false
            }
        }
    }

    fun signOut() {
        if (busy.value) return
        viewModelScope.launch {
            val session = sessions.current() ?: return@launch
            busy.value = true
            try {
                withContext(dispatchers.io) { accounts.deleteSession(session.token) }
                sessions.clear()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: OnlineApiException) {
                if (error.status == HttpUnauthorized) {
                    sessions.clear()
                } else {
                    logWarning(TAG, "sign-out failed", error)
                    messages.send(UiMessage(R.string.error_sign_out_failed))
                }
            } catch (error: Exception) {
                logWarning(TAG, "sign-out failed", error)
                messages.send(UiMessage(R.string.error_sign_out_failed))
            } finally {
                busy.value = false
            }
        }
    }

    private fun submit(
        email: String,
        password: String,
        failureRes: Int,
        call: suspend (String, String) -> AccountSession,
    ) {
        if (busy.value) return
        val emailText = email.trim()
        if (emailText.isEmpty() || password.isBlank()) {
            viewModelScope.launch { messages.send(UiMessage(failureRes)) }
            return
        }
        viewModelScope.launch {
            busy.value = true
            try {
                val session = withContext(dispatchers.io) { call(emailText, password) }
                finishSignIn(session)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "account request failed", error)
                messages.send(UiMessage(failureRes))
            } finally {
                busy.value = false
            }
        }
    }

    private fun rejectRegistration(email: String, password: String, confirmation: String): Boolean {
        if (email.isEmpty() || password.isBlank() || confirmation.isBlank()) {
            viewModelScope.launch { messages.send(UiMessage(R.string.error_create_account_failed)) }
            return true
        }
        if (!email.matches(emailPattern)) {
            viewModelScope.launch { messages.send(UiMessage(R.string.error_invalid_email)) }
            return true
        }
        if (password != confirmation) {
            viewModelScope.launch { messages.send(UiMessage(R.string.error_password_mismatch)) }
            return true
        }
        return false
    }

    private suspend fun finishSignIn(session: AccountSession) {
        sessions.save(session)
        reportPendingShare()
    }

    private suspend fun reportPendingShare() {
        try {
            val sharedProjectId = share.completePending()
            if (sharedProjectId != null) messages.send(UiMessage(R.string.share_completed))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logWarning(TAG, "share after sign-in failed", error)
            messages.send(UiMessage(R.string.error_share_failed))
        }
    }

    private companion object {
        const val TAG = "CommunityViewModel"
        val emailPattern = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
    }
}
