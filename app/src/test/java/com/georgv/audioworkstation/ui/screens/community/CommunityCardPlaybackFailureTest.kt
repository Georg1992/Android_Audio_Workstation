package com.georgv.audioworkstation.ui.screens.community

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.online.share.CardMedia
import com.georgv.audioworkstation.ui.playback.CardTransport
import java.io.File
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityCardPlaybackFailureTest {
    @Before
    fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test
    fun playReportsAConnectionProblemWhenTheHostCannotBeReached() = runTest {
        val vm = viewModel(ScriptedPlayback(UnknownHostException("Unable to resolve host")))
        vm.play("song")
        advanceUntilIdle()
        assertEquals(R.string.error_no_connection, vm.userMessages.first().resId)
    }

    @Test
    fun playKeepsThePlaybackFailureWhenTheMixIsRejected() = runTest {
        val vm = viewModel(ScriptedPlayback(IllegalStateException("mix stream status 200")))
        vm.play("song")
        advanceUntilIdle()
        assertEquals(R.string.error_card_playback_failed, vm.userMessages.first().resId)
    }

    @Test
    fun stallReportsTheConnectionOnceWhilePlaybackCanResume() = runTest {
        val player = ScriptedPlayback(stall = UnknownHostException("Unable to resolve host"))
        val vm = viewModel(player)
        vm.play("song")
        advanceUntilIdle()
        assertEquals(R.string.error_no_connection, vm.userMessages.first().resId)
        advanceUntilIdle()
        assertEquals(1, player.stalls)
        assertTrue("pulls=${player.pulls} reads=${player.reads}", player.pulls > 1)
        assertTrue(player.reads > 1)
    }

    private fun viewModel(player: CardPlayback): CommunityCardPlaybackViewModel =
        CommunityCardPlaybackViewModel(player, StaticMedia)

    private class ScriptedPlayback(
        private val playError: Exception? = null,
        private val stall: Exception? = null,
    ) : CardPlayback {
        var reads = 0
        var pulls = 0
        var stalls = 0
        private var transport = CardTransport(projectId = "song", playing = true, durationMs = 4_000)

        override fun play(projectId: String, url: String) {
            val error = playError
            if (error != null) throw error
        }

        override fun stop() {
            transport = transport.copy(playing = false)
        }

        override fun seekTo(positionMs: Int) = Unit

        override fun read(): CardTransport {
            reads += 1
            if (reads >= ReadsBeforeStop) return transport.copy(playing = false)
            return transport
        }

        override fun pullFailure(): Exception? {
            pulls += 1
            val failure = if (pulls == 1) stall else null
            if (failure != null) stalls += 1
            return failure
        }

        override fun release() = Unit
    }

    private object StaticMedia : CardMedia {
        override suspend fun mixUrl(projectId: String): String = "https://mix.example/song"

        override suspend fun image(projectId: String, hash: String): File = error("unused")
    }

    private companion object {
        const val ReadsBeforeStop = 4
    }
}
