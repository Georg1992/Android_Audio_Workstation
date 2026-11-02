package com.georgv.audioworkstation.ui.screens.community

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.online.CognitoRedirects
import com.georgv.audioworkstation.online.GoogleCognitoSignIn
import com.georgv.audioworkstation.online.network.HttpCall
import com.georgv.audioworkstation.online.network.HttpResult
import com.georgv.audioworkstation.online.network.HttpTransport
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CommunitySignInFailureTest {
    @Before
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun signInReportsAConnectionProblemWhenTheHostCannotBeReached() = runTest {
        val vm = viewModel(UnknownHostException("Unable to resolve host"))
        vm.onEmailChange("user@example.com")
        vm.onPasswordChange("secret")
        vm.signIn()
        advanceUntilIdle()
        assertEquals(R.string.error_no_connection, vm.userMessages.first().resId)
    }

    @Test
    fun signInKeepsTheSignInFailureWhenTheServerRejectsIt() = runTest {
        val vm = viewModel(IllegalStateException("sign-in failed"))
        vm.onEmailChange("user@example.com")
        vm.onPasswordChange("secret")
        vm.signIn()
        advanceUntilIdle()
        assertEquals(R.string.error_sign_in_failed, vm.userMessages.first().resId)
    }

    private fun viewModel(failure: Exception): CommunityViewModel {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return CommunityViewModel(
            sessions = MemorySessions(),
            accounts = FailingAccounts(failure),
            google = GoogleCognitoSignIn(
                context = context,
                redirects = IdleRedirects,
                transport = IdleTransport,
                io = Dispatchers.Unconfined,
                main = Dispatchers.Unconfined,
            ),
        )
    }

    private class MemorySessions : AccountSessionStore {
        private val sessions = MutableStateFlow<AccountSession?>(null)
        override val state: Flow<AccountSession?> = sessions
        override suspend fun current(): AccountSession? = sessions.value
        override suspend fun save(session: AccountSession) {
            sessions.value = session
        }
        override suspend fun clear() {
            sessions.value = null
        }
    }

    private class FailingAccounts(private val failure: Exception) : CognitoAccounts {
        override suspend fun register(email: String, password: String, name: String) = throw failure
        override suspend fun confirm(email: String, code: String) = throw failure
        override suspend fun signIn(email: String, password: String): AccountSession = throw failure
        override suspend fun refresh(session: AccountSession): AccountSession = throw failure
        override suspend fun signOut(refreshToken: String) = throw failure
    }

    private object IdleRedirects : CognitoRedirects {
        override fun offer(uri: android.net.Uri?) = Unit
        override suspend fun next(): android.net.Uri = error("unused")
    }

    private object IdleTransport : HttpTransport {
        override fun exchange(call: HttpCall): HttpResult = error("unused")
    }
}
