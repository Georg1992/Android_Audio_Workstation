package com.georgv.audioworkstation.ui.screens.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage
import com.georgv.audioworkstation.core.util.logWarning
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.CognitoRejected
import com.georgv.audioworkstation.online.CognitoSignInCancelled
import com.georgv.audioworkstation.online.GoogleCognitoSignIn
import com.georgv.audioworkstation.online.profileName
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommunityForm(
    val profileName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val confirmationCode: String = "",
    val awaitingCode: Boolean = false,
)

data class CommunityUiState(
    val sessionKnown: Boolean = false,
    val signedInEmail: String? = null,
    val profileName: String = "",
    val email: String = "",
    val password: String = "",
    val confirmPassword: String = "",
    val confirmationCode: String = "",
    val awaitingCode: Boolean = false,
    val busy: Boolean = false,
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val sessions: AccountSessionStore,
    private val accounts: CognitoAccounts,
    private val google: GoogleCognitoSignIn,
) : ViewModel() {
    private val form = MutableStateFlow(CommunityForm())
    private val busy = MutableStateFlow(false)
    private val messages = Channel<UiMessage>(capacity = Channel.BUFFERED)
    val userMessages = messages.receiveAsFlow()

    val uiState: StateFlow<CommunityUiState> =
        combine(sessions.state, form, busy) { session, fields, isBusy ->
            CommunityUiState(
                sessionKnown = true,
                signedInEmail = session?.email,
                profileName = fields.profileName,
                email = fields.email,
                password = fields.password,
                confirmPassword = fields.confirmPassword,
                confirmationCode = fields.confirmationCode,
                awaitingCode = fields.awaitingCode,
                busy = isBusy,
            )
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            CommunityUiState(),
        )

    fun onProfileNameChange(value: String) {
        form.update { it.copy(profileName = value) }
    }

    fun onEmailChange(value: String) {
        form.update { it.copy(email = value) }
    }

    fun onPasswordChange(value: String) {
        form.update { it.copy(password = value) }
    }

    fun onConfirmPasswordChange(value: String) {
        form.update { it.copy(confirmPassword = value) }
    }

    fun onCodeChange(value: String) {
        form.update { it.copy(confirmationCode = value) }
    }

    fun showSignIn() {
        form.update { it.copy(awaitingCode = false) }
    }

    fun register() {
        val fields = form.value
        val email = normalizedEmail(fields.email)
        val problem = registerProblem(email, fields.password, fields.confirmPassword)
        if (problem != null) {
            report(problem)
            return
        }
        submit(R.string.error_create_account_failed) {
            accounts.register(email, fields.password, profileName(fields.profileName, email))
            form.update { it.copy(awaitingCode = true, confirmationCode = "") }
            messages.send(UiMessage(R.string.community_check_email))
        }
    }

    fun confirm() {
        val fields = form.value
        val email = normalizedEmail(fields.email)
        val code = fields.confirmationCode.trim()
        val problem = confirmProblem(email, code)
        if (problem != null) {
            report(problem)
            return
        }
        submit(R.string.error_confirmation_code) {
            accounts.confirm(email, code)
            form.update { it.copy(awaitingCode = false, confirmationCode = "") }
            sessions.save(accounts.signIn(email, fields.password))
        }
    }

    fun signIn() {
        val fields = form.value
        val email = normalizedEmail(fields.email)
        val problem = signInProblem(email, fields.password)
        if (problem != null) {
            report(problem)
            return
        }
        submit(R.string.error_sign_in_failed) {
            sessions.save(accounts.signIn(email, fields.password))
        }
    }

    fun signInWithGoogle() {
        submit(R.string.error_gmail_sign_in_failed) {
            sessions.save(google.signIn())
        }
    }

    private fun report(message: Int) {
        viewModelScope.launch { messages.send(UiMessage(message)) }
    }

    private fun submit(failure: Int, block: suspend () -> Unit) {
        if (busy.value) return
        busy.value = true
        viewModelScope.launch {
            try {
                block()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: CognitoSignInCancelled) {
            } catch (error: CognitoRejected) {
                onRejected(error)
            } catch (error: Exception) {
                logWarning(TAG, "account request failed", error)
                messages.send(UiMessage(failure))
            } finally {
                busy.value = false
            }
        }
    }

    private suspend fun onRejected(error: CognitoRejected) {
        if (error.type == USER_NOT_CONFIRMED) {
            form.update { it.copy(awaitingCode = true) }
            messages.send(UiMessage(R.string.community_check_email))
            return
        }
        messages.send(UiMessage(cognitoMessage(error.type)))
    }

    private companion object {
        const val TAG = "CommunityViewModel"
        const val STOP_TIMEOUT_MS = 5_000L
        const val USER_NOT_CONFIRMED = "UserNotConfirmedException"
    }
}
