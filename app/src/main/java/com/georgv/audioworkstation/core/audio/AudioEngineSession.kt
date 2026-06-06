package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Reference-counts active project-screen consumers of the process-wide native engine.
 *
 * Teardown runs only when the last session is released, and acquire/release are serialized
 * on [AppDispatchers.audioIo] so a new screen cannot start transport while teardown is in flight.
 *
 * [parameterEpoch] increments when the last screen begins release so [AudioParameterCommandQueue]
 * drops stale live-parameter commands before native teardown.
 */
@Singleton
class AudioEngineSession @Inject constructor(
    private val dispatchers: AppDispatchers,
) {
    private val mutex = Mutex()
    private val activeProjectScreens = AtomicInteger(0)
    private val parameterEpoch = AtomicLong(0L)

    /** Epoch captured when enqueueing live gain/pan; apply only when still current. */
    fun parameterEpoch(): Long = parameterEpoch.get()

    fun hasActiveProjectScreens(): Boolean = activeProjectScreens.get() > 0

    suspend fun acquire() {
        withContext(dispatchers.audioIo) {
            mutex.withLock {
                activeProjectScreens.incrementAndGet()
            }
        }
    }

    /**
     * Decrements the active screen count. [onLastSessionReleased] runs on [AppDispatchers.audioIo]
     * while the lifecycle mutex is held when the count reaches zero.
     */
    suspend fun release(onLastSessionReleased: suspend () -> Unit) {
        withContext(dispatchers.audioIo) {
            mutex.withLock {
                val current = activeProjectScreens.get()
                if (current == 1) {
                    parameterEpoch.incrementAndGet()
                }
                val remaining = (current - 1).coerceAtLeast(0)
                activeProjectScreens.set(remaining)
                if (remaining == 0) {
                    onLastSessionReleased()
                }
            }
        }
    }

    internal fun activeSessionCountForTests(): Int = activeProjectScreens.get()

    internal fun parameterEpochForTests(): Long = parameterEpoch.get()
}
