package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoSignIn
import com.georgv.audioworkstation.online.CognitoSignInCancelled
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

data class CommunityUiState(
    val signedInEmail: String? = null,
    val busy: Boolean = false,
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val sessions: AccountSessionStore,
    private val cognito: CognitoSignIn,
) : ViewModel() {
    private val busy = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    val uiState: StateFlow<CommunityUiState> =
        combine(sessions.state, busy) { session, isBusy ->
            CommunityUiState(signedInEmail = session?.email, busy = isBusy)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            CommunityUiState(),
        )

    fun signIn() {
        if (busy.value) return
        viewModelScope.launch {
            busy.value = true
            try {
                sessions.save(cognito.signIn())
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: CognitoSignInCancelled) {
            } catch (error: Exception) {
                logWarning(TAG, "sign-in failed", error)
                messages.send(UiMessage(R.string.error_sign_in_failed))
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
                cognito.signOut(session.refreshToken)
                sessions.clear()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                logWarning(TAG, "sign-out failed", error)
                messages.send(UiMessage(R.string.error_sign_out_failed))
            } finally {
                busy.value = false
            }
        }
    }

    private companion object {
        const val TAG = "CommunityViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
