package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.TestAppDispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioEngineSessionTest {

    private val audioIoDispatcher = StandardTestDispatcher()
    private val dispatchers = TestAppDispatchers.unified(audioIoDispatcher)

    @Test
    fun release_bumpsParameterEpoch() = runTest(audioIoDispatcher) {
        val session = AudioEngineSession(dispatchers)
        val epochBefore = session.parameterEpochForTests()
        session.acquire()
        session.release { }
        assertEquals(epochBefore + 1L, session.parameterEpochForTests())
    }

    @Test
    fun release_doesNotTeardownUntilLastSessionReleased() = runTest(audioIoDispatcher) {
        val session = AudioEngineSession(dispatchers)
        var teardownCount = 0

        session.acquire()
        session.acquire()
        session.release { teardownCount++ }
        assertEquals(0, teardownCount)
        assertEquals(1, session.activeSessionCountForTests())

        session.release { teardownCount++ }
        assertEquals(1, teardownCount)
        assertEquals(0, session.activeSessionCountForTests())
    }

    @Test
    fun acquireAfterFullReleaseAllowsNewSession() = runTest(audioIoDispatcher) {
        val session = AudioEngineSession(dispatchers)
        var teardownCount = 0

        session.acquire()
        session.release { teardownCount++ }
        assertEquals(1, teardownCount)

        session.acquire()
        assertEquals(1, session.activeSessionCountForTests())
        session.release { teardownCount++ }
        assertEquals(2, teardownCount)
        assertTrue(session.activeSessionCountForTests() == 0)
    }
}
