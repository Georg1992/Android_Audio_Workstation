package com.georgv.audioworkstation.ui.screens.library

import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.core.audio.TransportTimelinePolicy
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.playbackStartAllowedAtPlayhead
import com.georgv.audioworkstation.core.audio.sessionPlaybackSchedulingEndMsForTracks
import com.georgv.audioworkstation.core.audio.toLiveEnginePlaybackSpec
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.core.track.hasPersistedPlayableAudio
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Library card playback through the same native track mix as the project screen.
 * A scrub pauses that engine, then starts the tracks again from the released playhead.
 */
@Singleton
class LibraryMixPreviewPlayer @Inject constructor(
    private val playback: PlaybackPort,
    private val meter: MeterPort,
    private val sessionTransportGate: SessionTransportCapabilityGate,
    private val dispatchers: AppDispatchers,
) {
    private val engine = Mutex()
    private var armedProject: ProjectEntity? = null
    private var armedTracks: List<TrackEntity> = emptyList()
    private var loadedProjectId: String? = null
    private var timelineEndMs = 0L
    private var positionMs = 0
    private var holding = false
    private var heardEngine = false
    private var outputLatencyMs = 0.0

    private val playingProjectId = MutableStateFlow<String?>(null)
    val playingProjectIdState: StateFlow<String?> = playingProjectId.asStateFlow()

    private val transport = MutableStateFlow(MixTransport())
    val transportState: StateFlow<MixTransport> = transport.asStateFlow()

    fun isPlaying(projectId: String): Boolean = playingProjectId.value == projectId

    suspend fun togglePreview(project: ProjectEntity, tracks: List<TrackEntity>): Boolean {
        if (playingProjectId.value == project.id) {
            stopAll()
            return false
        }
        return play(project, tracks)
    }

    suspend fun play(project: ProjectEntity, tracks: List<TrackEntity>): Boolean = engine.withLock {
        if (playingProjectId.value == project.id && !holding) return true
        val previousId = loadedProjectId
        if (!rememberTracks(project, tracks)) return false
        if (previousId != project.id) positionMs = 0
        if (positionMs.toLong() >= timelineEndMs && armedTracks.none { it.isLoop }) positionMs = 0
        prepareSession(project.sampleRate)
        playingProjectId.value = project.id
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

    /** Pause the engine while the finger is down. The playhead stays armed. */
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
            val endMs = timelineEndMs
            if (armedProject == null || endMs <= 0L) return
            val duration = endMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
            if (playingProjectId.value == null || holding || timelineEndMs <= 0L) return
            val sample = withAudioIo(dispatchers, "MeterPort.transportPositionMs") {
                val running = playback.isPlaybackEngineRunning()
                val audible = if (running) {
                    PlaybackTransportSync.audiblePlayheadMsFromRaw(meter, meter.transportPositionMs()).value
                } else {
                    0L
                }
                running to audible
            }
            val endMs = timelineEndMs
            val (running, audible) = sample
            if (running) {
                heardEngine = true
                positionMs = audible.coerceIn(0L, endMs).toInt()
            } else if (heardEngine) {
                playingProjectId.value = null
                positionMs = endMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
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
        armedProject = null
        armedTracks = emptyList()
        loadedProjectId = null
        timelineEndMs = 0L
        positionMs = 0
        holding = false
        heardEngine = false
        outputLatencyMs = 0.0
        playingProjectId.value = null
        publishLocked()
    }

    private suspend fun prepareSession(sampleRate: Int) {
        withAudioIo(dispatchers, "prepare session transport capability") {
            withIo(dispatchers, "prepare session transport capability") {
                sessionTransportGate.prepareForLiveSession(sampleRate)
            }
        }
    }

    private suspend fun armBody(positionMs: Int): Boolean {
        val current = armedProject ?: return false
        val playable = armedTracks
        val timelineEnd = timelineEndMs
        if (playable.isEmpty() || timelineEnd <= 0L) return false
        val audible = TransportTimelinePolicy.playbackStartPositionMsForTracks(
            scrubbedPlayheadMs = positionMs.toLong().coerceIn(0L, timelineEnd),
            timelineVisibleDurationMs = timelineEnd,
            tracks = playable,
        )
        if (!playbackStartAllowedAtPlayhead(audible.value, timelineEnd, playable)) return false
        return withAudioIo(dispatchers, "PlaybackPort.startPlayback") {
            val liveLatency = PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(meter)
            val latencyMs = if (liveLatency > 0.0) liveLatency else outputLatencyMs
            PlaybackTransportSync.requirePreparedCapability(sessionTransportGate)
            val spec = current.toLiveEnginePlaybackSpec(
                tracks = playable,
                startPositionMs = PlaybackTransportSync.mixTransportMs(audible, latencyMs),
            ) ?: return@withAudioIo false
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

    private fun rememberTracks(project: ProjectEntity, tracks: List<TrackEntity>): Boolean {
        val playable = tracks.filter { track -> track.hasPersistedPlayableAudio() }
        if (playable.isEmpty()) return false
        val endMs = sessionPlaybackSchedulingEndMsForTracks(playable)
        if (endMs <= 0L) return false
        armedProject = project
        armedTracks = playable
        loadedProjectId = project.id
        timelineEndMs = endMs
        return true
    }

    private fun publishLocked() {
        val id = loadedProjectId
        val endMs = timelineEndMs
        if (id == null || endMs <= 0L) {
            transport.value = MixTransport()
            return
        }
        transport.value = MixTransport(
            projectId = id,
            playing = playingProjectId.value != null,
            positionMs = positionMs,
            durationMs = endMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )
    }
}

data class MixTransport(
    val projectId: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
)
