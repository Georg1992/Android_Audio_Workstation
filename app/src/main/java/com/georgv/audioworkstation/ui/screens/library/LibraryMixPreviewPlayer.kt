package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.core.audio.AudibleMs
import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.core.audio.ProjectSampleRate
import com.georgv.audioworkstation.core.audio.TrackPlaybackLane
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.readWavPcmFileInfo
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.ui.playback.CardTransport
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Library card playback of the finished mix file.
 * A scrub pauses that file, then starts it again from the released playhead.
 */
@Singleton
class LibraryMixPreviewPlayer @Inject constructor(
    private val playback: PlaybackPort,
    private val meter: MeterPort,
    private val sessionTransportGate: SessionTransportCapabilityGate,
    private val dispatchers: AppDispatchers,
) {
    private val engine = Mutex()
    private var clip: MixClip? = null
    private var positionMs = 0
    private var holding = false
    private var heardEngine = false
    private var outputLatencyMs = 0.0

    private val playingProjectId = MutableStateFlow<String?>(null)
    val playingProjectIdState: StateFlow<String?> = playingProjectId.asStateFlow()

    private val transport = MutableStateFlow(CardTransport())
    val transportState: StateFlow<CardTransport> = transport.asStateFlow()

    fun isPlaying(projectId: String): Boolean = playingProjectId.value == projectId

    suspend fun play(projectId: String, wavPath: String): Boolean = engine.withLock {
        if (playingProjectId.value == projectId && !holding) return true
        val previous = clip
        if (previous?.projectId != projectId || previous.path != wavPath) {
            if (!loadClip(projectId, wavPath)) return false
            positionMs = 0
        }
        val loaded = clip ?: return false
        if (positionMs.toLong() >= loaded.durationMs) positionMs = 0
        prepareSession(loaded.sampleRate)
        playingProjectId.value = projectId
        holding = false
        heardEngine = false
        val started = armBody(positionMs)
        if (!started) playingProjectId.value = null
        publishLocked()
        started
    }

    suspend fun stop() = engine.withLock {
        holding = false
        heardEngine = false
        playingProjectId.value = null
        positionMs = 0
        haltBody()
        publishLocked()
    }

    /** Pause the mix while the finger is down. The playhead stays armed. */
    suspend fun holdForScrub() {
        engine.withLock {
            if (playingProjectId.value == null || holding) return
            holding = true
            heardEngine = false
            haltBody()
            publishLocked()
        }
    }

    suspend fun seekTo(positionMs: Int) {
        engine.withLock {
            val loaded = clip ?: return
            val duration = loaded.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            this.positionMs = positionMs.coerceIn(0, duration)
            holding = false
            if (playingProjectId.value != null) {
                heardEngine = false
                val started = armBody(this.positionMs)
                if (!started) playingProjectId.value = null
            }
            publishLocked()
        }
    }

    suspend fun tick() {
        engine.withLock {
            if (playingProjectId.value == null || holding || clip == null) return
            val sample = withAudioIo(dispatchers, "MeterPort.transportPositionMs") {
                val running = playback.isPlaybackEngineRunning()
                val audible = if (running) {
                    PlaybackTransportSync.audiblePlayheadMsFromRaw(meter, meter.transportPositionMs()).value
                } else {
                    0L
                }
                running to audible
            }
            val loaded = clip ?: return
            val (running, audible) = sample
            if (running) {
                heardEngine = true
                positionMs = audible.coerceIn(0L, loaded.durationMs).toInt()
            } else if (heardEngine) {
                playingProjectId.value = null
                positionMs = loaded.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            }
            publishLocked()
        }
    }

    suspend fun stopIfPlaying(projectId: String) {
        if (playingProjectId.value != projectId) return
        stopAll()
    }

    suspend fun stopAll() = engine.withLock {
        haltBody()
        clip = null
        positionMs = 0
        holding = false
        heardEngine = false
        outputLatencyMs = 0.0
        playingProjectId.value = null
        publishLocked()
    }

    private suspend fun prepareSession(sampleRate: Int) {
        withIo(dispatchers, "prepare session transport capability") {
            sessionTransportGate.prepareForLiveSession(sampleRate)
        }
    }

    private suspend fun armBody(positionMs: Int): Boolean {
        val loaded = clip ?: return false
        val audible = AudibleMs(positionMs.toLong().coerceIn(0L, loaded.durationMs))
        return withAudioIo(dispatchers, "PlaybackPort.startPlayback") {
            val liveLatency = PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(meter)
            val latencyMs = if (liveLatency > 0.0) liveLatency else outputLatencyMs
            PlaybackTransportSync.requirePreparedCapability(sessionTransportGate)
            val spec = MultiPlaybackSpec(
                sampleRate = loaded.sampleRate,
                lanes = listOf(
                    TrackPlaybackLane(
                        trackId = loaded.projectId,
                        wavFilePath = loaded.path,
                        gain = 1f,
                        timelineClipDurationMs = loaded.durationMs,
                    ),
                ),
                startPositionMs = PlaybackTransportSync.mixTransportMs(audible, latencyMs).value,
                sessionTimelineEndMs = loaded.durationMs,
            )
            playback.stopPlayback()
            playback.startPlayback(spec)
        }
    }

    private suspend fun haltBody() {
        withAudioIo(dispatchers, "PlaybackPort.stopPlayback") {
            val liveLatency = PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(meter)
            if (liveLatency > 0.0) outputLatencyMs = liveLatency
            playback.stopPlayback()
        }
    }

    private suspend fun loadClip(projectId: String, wavPath: String): Boolean {
        val info = withIo(dispatchers, "read mix wav") { readWavPcmFileInfo(wavPath) } ?: return false
        if (!info.isSupportedProjectPcm || info.durationMs <= 0L) return false
        if (ProjectSampleRate.entries.none { rate -> rate.hz == info.sampleRateHz }) return false
        clip = MixClip(
            projectId = projectId,
            path = wavPath,
            sampleRate = info.sampleRateHz,
            durationMs = info.durationMs,
        )
        return true
    }

    private fun publishLocked() {
        val loaded = clip
        if (loaded == null || loaded.durationMs <= 0L) {
            transport.value = CardTransport()
            return
        }
        transport.value = CardTransport(
            projectId = loaded.projectId,
            playing = playingProjectId.value != null,
            positionMs = positionMs,
            durationMs = loaded.durationMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}

private data class MixClip(
    val projectId: String,
    val path: String,
    val sampleRate: Int,
    val durationMs: Long,
)
