package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.ui.screens.community.CommunityHomeViewModel
import com.georgv.audioworkstation.ui.screens.community.LocalProjectLookup
import com.georgv.audioworkstation.ui.screens.community.OwnedShareRefresh
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
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
            """{"projects":[{"id":"project-a","title":"Song","tags":["live"],"imageHash":null,"ownerName":"Ada"},{"id":"project-b","title":"","tags":[],"imageHash":"abc","ownerName":"Georg"}]}""",
        )
        assertEquals(listOf("project-a", "project-b"), projects.map { it.id })
        assertEquals(listOf("Song", ""), projects.map { it.title })
        assertEquals(listOf("live"), projects[0].tags)
        assertEquals(null, projects[0].imageHash)
        assertEquals("abc", projects[1].imageHash)
        assertEquals(listOf("Ada", "Georg"), projects.map { it.ownerName })
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun signedInCommunityShowsSharedProjects() = runTest {
        val vm = CommunityHomeViewModel(
            sessions = MemorySessions(session),
            accounts = UnusedAccounts,
            sharedProjects = FixedProjects(
                listOf(SharedProject("project-a", "Song", emptyList(), null, "Ada")),
            ),
            localProjects = NoLocalProjects,
            ownedShares = NoLocalProjects,
        )
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(listOf("Song"), vm.uiState.value.projects.map { it.title })
        assertEquals("owner@example.com", vm.uiState.value.email)
        collect.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun signOutClearsSessionWhenRevokeFails() = runTest {
        val sessions = MemorySessions(session)
        val vm = CommunityHomeViewModel(
            sessions = sessions,
            accounts = RevokeFails,
            sharedProjects = FixedProjects(emptyList()),
            localProjects = NoLocalProjects,
            ownedShares = NoLocalProjects,
        )
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        vm.signOut()
        advanceUntilIdle()
        assertEquals(null, vm.uiState.value.email)
        collect.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun communityLoadReportsAConnectionProblem() = runTest {
        val vm = communityHome(UnknownHostException("Unable to resolve host"))
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(R.string.error_no_connection, vm.userMessages.first().resId)
        collect.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun communityLoadKeepsTheLoadFailureWhenTheServerRejectsIt() = runTest {
        val vm = communityHome(IllegalStateException("share api status 500"))
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(R.string.error_shared_projects_failed, vm.userMessages.first().resId)
        collect.cancel()
    }

    private fun communityHome(failure: Exception) = CommunityHomeViewModel(
        sessions = MemorySessions(session),
        accounts = UnusedAccounts,
        sharedProjects = object : SharedProjects {
            override suspend fun list(): List<SharedProject> = throw failure

            override suspend fun imageUrl(id: String): String = error("unused")

            override suspend fun playback(id: String): List<SharedPlayback> = error("unused")
        },
        localProjects = NoLocalProjects,
        ownedShares = NoLocalProjects,
    )

    private class FixedProjects(private val projects: List<SharedProject>) : SharedProjects {
        override suspend fun list(): List<SharedProject> = projects

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")
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

    private object RevokeFails : CognitoAccounts {
        override suspend fun register(email: String, password: String, name: String) = error("unused")

        override suspend fun confirm(email: String, code: String) = error("unused")

        override suspend fun signIn(email: String, password: String): AccountSession = error("unused")

        override suspend fun refresh(session: AccountSession): AccountSession = error("unused")

        override suspend fun signOut(refreshToken: String) = error("revoke failed")
    }

    private object UnusedAccounts : CognitoAccounts {
        override suspend fun register(email: String, password: String, name: String) = error("unused")

        override suspend fun confirm(email: String, code: String) = error("unused")

        override suspend fun signIn(email: String, password: String): AccountSession = error("unused")

        override suspend fun refresh(session: AccountSession): AccountSession = error("unused")

        override suspend fun signOut(refreshToken: String) = error("unused")
    }

    private object NoLocalProjects : LocalProjectLookup, OwnedShareRefresh {
        override suspend fun exists(projectId: String): Boolean = false

        override suspend fun publishOwnerNames() = Unit

        override suspend fun publishMixes() = Unit
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
