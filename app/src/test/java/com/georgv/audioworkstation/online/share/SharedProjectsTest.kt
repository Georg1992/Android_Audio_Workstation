package com.georgv.audioworkstation.online.share

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.audio.NoopProjectFileStore
import com.georgv.audioworkstation.core.audio.TempDirAudioFilePathProvider
import com.georgv.audioworkstation.data.db.dao.FakeProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.AccountSession
import com.georgv.audioworkstation.online.AccountSessionStore
import com.georgv.audioworkstation.online.CognitoAccounts
import com.georgv.audioworkstation.ui.screens.community.CommunityHomeViewModel
import com.georgv.audioworkstation.ui.screens.community.LocalProjectLookup
import com.georgv.audioworkstation.ui.screens.community.OwnedShareRefresh
import com.georgv.audioworkstation.ui.screens.community.RoomLocalProjects
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
import org.junit.Assert.assertTrue
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
    fun communityDropsAnOwnedShareThatIsNotOnThePhone() = runTest {
        val vm = CommunityHomeViewModel(
            sessions = MemorySessions(session),
            accounts = UnusedAccounts,
            sharedProjects = FixedProjects(
                listOf(
                    SharedProject("boom", "Boom", emptyList(), null, "selftitled1"),
                    SharedProject("other", "Song", emptyList(), null, "Ada"),
                ),
            ),
            localProjects = NoLocalProjects,
            ownedShares = object : OwnedShareRefresh {
                override suspend fun publishOwnerNames() = Unit

                override suspend fun publishMixes() = Unit

                override suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String> = setOf("boom")
            },
        )
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(listOf("Song"), vm.uiState.value.projects.map { it.title })
        collect.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun communityKeepsTheConnectionMessageWhenAnAbsentShareCannotBeUnpublished() = runTest {
        val vm = CommunityHomeViewModel(
            sessions = MemorySessions(session),
            accounts = UnusedAccounts,
            sharedProjects = FixedProjects(
                listOf(SharedProject("boom", "Boom", emptyList(), null, "selftitled1")),
            ),
            localProjects = NoLocalProjects,
            ownedShares = object : OwnedShareRefresh {
                override suspend fun publishOwnerNames() = Unit

                override suspend fun publishMixes() = Unit

                override suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String> {
                    throw UnknownHostException("Unable to resolve host")
                }
            },
        )
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        assertEquals(R.string.error_no_connection, vm.userMessages.first().resId)
        assertTrue(vm.uiState.value.projects.isEmpty())
        collect.cancel()
    }

    @Test
    fun absentOwnedShareIsUnpublishedAndAProjectOnThePhoneStaysShared() = runTest {
        val dao = FakeProjectDao(projects = listOf(ProjectEntity(id = "showcase", name = "Showcase")))
        val share = RecordingOwnedPublish(owned = setOf("boom"))
        val local = RoomLocalProjects(
            ProjectRepository(dao, NoopProjectFileStore),
            MemorySessions(session),
            share,
            TempDirAudioFilePathProvider(),
            CountingFiles(),
        )
        val removed = local.unpublishAbsent(
            listOf(
                SharedProject("boom", "Boom", emptyList(), null, "selftitled1"),
                SharedProject("other", "Song", emptyList(), null, "Ada"),
                SharedProject("showcase", "Showcase", emptyList(), null, "selftitled1"),
            ),
        )
        assertEquals(setOf("boom"), removed)
        assertEquals(listOf("boom", "other"), share.unpublished)
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

            override suspend fun files(id: String): List<SharedTake> = error("unused")
        },
        localProjects = NoLocalProjects,
        ownedShares = NoLocalProjects,
    )

    private class FixedProjects(private val projects: List<SharedProject>) : SharedProjects {
        override suspend fun list(): List<SharedProject> = projects

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

        override suspend fun files(id: String): List<SharedTake> = error("unused")
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

    private class RecordingOwnedPublish(private val owned: Set<String>) : OwnedProjectPublish {
        val unpublished = mutableListOf<String>()

        override suspend fun publishOwner(projectId: String) = Unit

        override suspend fun publishMix(projectId: String) = Unit

        override suspend fun unpublish(projectId: String): Boolean {
            unpublished += projectId
            return projectId in owned
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun openPlacesASharedProjectThatIsNotOnThePhone() = runTest {
        val lookup = RecordingLookup(existing = emptySet())
        val vm = CommunityHomeViewModel(
            sessions = MemorySessions(session),
            accounts = UnusedAccounts,
            sharedProjects = FixedProjects(
                listOf(SharedProject("song", "Song", emptyList(), null, "Ada")),
            ),
            localProjects = lookup,
            ownedShares = NoLocalProjects,
        )
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        vm.open("song")
        advanceUntilIdle()
        assertEquals(listOf("song" to "Song"), lookup.presented)
        assertEquals("song", vm.openProject.first())
        collect.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun openKeepsAProjectThatIsAlreadyOnThePhone() = runTest {
        val lookup = RecordingLookup(existing = setOf("song"))
        val vm = CommunityHomeViewModel(
            sessions = MemorySessions(session),
            accounts = UnusedAccounts,
            sharedProjects = FixedProjects(
                listOf(SharedProject("song", "Song", emptyList(), null, "Ada")),
            ),
            localProjects = lookup,
            ownedShares = NoLocalProjects,
        )
        val collect = backgroundScope.launch { vm.uiState.collect { } }
        advanceUntilIdle()
        vm.open("song")
        advanceUntilIdle()
        assertEquals(listOf("song" to "Song"), lookup.presented)
        assertEquals("song", vm.openProject.first())
        collect.cancel()
    }

    @Test
    fun presentPlacesAMissingSharedProjectAndLeavesAnExistingName() = runTest {
        val dao = FakeProjectDao(projects = listOf(ProjectEntity(id = "showcase", name = "Showcase")))
        val files = CountingFiles()
        val local = RoomLocalProjects(
            ProjectRepository(dao, NoopProjectFileStore),
            MemorySessions(session),
            RecordingOwnedPublish(emptySet()),
            TempDirAudioFilePathProvider(),
            files,
        )
        local.present("song", "Song")
        local.present("showcase", "Renamed")
        assertEquals("Song", dao.observeProject("song").first()?.name)
        assertEquals("song", dao.observeProject("song").first()?.remoteUrl)
        assertEquals("Showcase", dao.observeProject("showcase").first()?.name)
        assertEquals("showcase", dao.observeProject("showcase").first()?.remoteUrl)
        assertEquals(listOf("song", "showcase"), files.ids)
        assertTrue(dao.observeTracks("song").first().isEmpty())
    }

    private class CountingFiles : SharedProjects {
        val ids = mutableListOf<String>()

        override suspend fun list(): List<SharedProject> = error("unused")

        override suspend fun imageUrl(id: String): String = error("unused")

        override suspend fun playback(id: String): List<SharedPlayback> = error("unused")

        override suspend fun files(id: String): List<SharedTake> {
            ids += id
            return emptyList()
        }
    }

    private class RecordingLookup(private val existing: Set<String>) : LocalProjectLookup {
        val presented = mutableListOf<Pair<String, String>>()

        override suspend fun exists(projectId: String): Boolean = projectId in existing

        override suspend fun present(projectId: String, title: String) {
            presented += projectId to title
        }
    }

    private object NoLocalProjects : LocalProjectLookup, OwnedShareRefresh {
        override suspend fun exists(projectId: String): Boolean = false

        override suspend fun present(projectId: String, title: String) = Unit

        override suspend fun publishOwnerNames() = Unit

        override suspend fun publishMixes() = Unit

        override suspend fun unpublishAbsent(projects: List<SharedProject>): Set<String> = emptySet()
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
