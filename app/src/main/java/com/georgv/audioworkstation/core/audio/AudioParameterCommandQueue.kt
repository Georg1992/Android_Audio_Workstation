package com.georgv.audioworkstation.core.audio

import android.util.Log
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Coalesced last-value queue for live per-lane gain/pan updates.
 *
 * UI/ViewModel enqueue from Main; a single consumer on [AppDispatchers.audioParam] applies JNI.
 * See [AudioThreadingContract].
 */
@Singleton
class AudioParameterCommandQueue @Inject constructor(
    private val playback: PlaybackPort,
    private val dispatchers: AppDispatchers,
    private val audioEngineSession: AudioEngineSession,
) {
    private enum class ParamKind { GAIN, PAN }

    private data class ParamKey(val laneIndex: Int, val kind: ParamKind)

    private data class PendingUpdate(
        val epoch: Long,
        val value: Float,
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatchers.audioParam)
    private val pending = ConcurrentHashMap<ParamKey, PendingUpdate>()
    private val wake = Channel<Unit>(capacity = Channel.CONFLATED)

    init {
        scope.launch {
            while (true) {
                wake.receive()
                try {
                    drainPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "drainPending failed; consumer continues", e)
                }
            }
        }
    }

    fun setLaneGain(laneIndex: Int, gain: Float) {
        enqueue(laneIndex, ParamKind.GAIN, gain)
    }

    fun setLanePan(laneIndex: Int, pan: Float) {
        enqueue(laneIndex, ParamKind.PAN, pan)
    }

    /** Clears coalesced pending updates (project change, transport stop). */
    fun clearPending() {
        pending.clear()
    }

    private fun enqueue(laneIndex: Int, kind: ParamKind, value: Float) {
        if (laneIndex < 0) return
        if (!audioEngineSession.hasActiveProjectScreens()) return
        val epoch = audioEngineSession.parameterEpoch()
        pending[ParamKey(laneIndex, kind)] = PendingUpdate(epoch, value)
        wake.trySend(Unit)
    }

    private fun drainPending() {
        if (!audioEngineSession.hasActiveProjectScreens()) {
            pending.clear()
            return
        }
        val currentEpoch = audioEngineSession.parameterEpoch()
        for (key in pending.keys.toList()) {
            val update = pending.remove(key) ?: continue
            if (update.epoch != currentEpoch) continue
            if (!audioEngineSession.hasActiveProjectScreens()) {
                pending.clear()
                return
            }
            when (key.kind) {
                ParamKind.GAIN -> playback.setPlaybackLaneGain(key.laneIndex, update.value)
                ParamKind.PAN -> playback.setPlaybackLanePan(key.laneIndex, update.value)
            }
        }
    }

    internal fun pendingCountForTests(): Int = pending.size

    private companion object {
        const val TAG = "AudioParameterCommandQueue"
    }
}
