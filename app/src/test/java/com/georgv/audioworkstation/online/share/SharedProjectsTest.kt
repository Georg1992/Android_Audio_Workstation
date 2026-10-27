package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.ui.screens.community.CommunityHomeViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

class SharedProjectsTest {
    @get:Rule
    val mainDispatcher = object : TestWatcher() {
        override fun starting(description: Description) {
            Dispatchers.setMain(StandardTestDispatcher())
        }

        override fun finished(description: Description) {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun listKeepsIdAndTitle() {
        val projects = sharedProjects(
            """{"projects":[{"id":"project-a","title":"Song"},{"id":"project-b","title":""}]}""",
        )
        assertEquals(listOf("project-a", "project-b"), projects.map { it.id })
        assertEquals(listOf("Song", ""), projects.map { it.title })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun signedInCommunityShowsSharedProjects() = runTest {
        val vm = CommunityHomeViewModel(
            sessions = MemorySessions(session),
            accounts = UnusedAccounts,
            sharedProjects = FixedProjects(listOf(SharedProject("project-a", "Song"))),
        )
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(listOf("Song"), vm.uiState.value.projects.map { it.title })
        assertEquals("owner@example.com", vm.uiState.value.email)
        collect.cancel()
    }

    private class FixedProjects(private val projects: List<SharedProject>) : SharedProjects {
        override suspend fun list(): List<SharedProject> = projects
    }

    private class MemorySessions(session: AccountSession?) : AccountSessionStore {
        private val sessions = MutableStateFlow(session)

        override val state: Flow<AccountSession?> = sessions

        override suspend fun current(): AccountSession? = sessions.value

        override suspend fun save(session: AccountSession) {
            sessions.value = session
        }

        override suspend fun clear() {
            sessions.value = null
        }
    }

    private object UnusedAccounts : CognitoAccounts {
        override suspend fun register(email: String, password: String, name: String) = error("unused")

        override suspend fun confirm(email: String, code: String) = error("unused")

        override suspend fun signIn(email: String, password: String): AccountSession = error("unused")

        override suspend fun refresh(session: AccountSession): AccountSession = error("unused")

        override suspend fun signOut(refreshToken: String) = error("unused")
    }

    private companion object {
        val session = AccountSession(
            token = "access-token",
            accountId = "account-1",
            email = "owner@example.com",
            name = "Owner",
            refreshToken = "refresh-token",
        )
    }
}
