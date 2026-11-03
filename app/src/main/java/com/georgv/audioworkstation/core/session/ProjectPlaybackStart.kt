package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.core.audio.AudibleMs
import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.MixTransportMs
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.sessionPlaybackSchedulingEndMsForPlayback
import com.georgv.audioworkstation.core.audio.toLiveEnginePlaybackSpec
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.core.coroutines.withAudioIo
import com.georgv.audioworkstation.core.coroutines.withIo
import com.georgv.audioworkstation.core.track.streamsSharedTake
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import kotlinx.coroutines.CancellationException

internal class PlaybackArm(val laneIds: List<String>)

internal class ProjectPlaybackStart(
    private val playback: PlaybackPort,
    private val meter: MeterPort,
    private val dispatchers: AppDispatchers,
    private val sessionTransportGate: SessionTransportCapabilityGate,
) {
    suspend fun start(
        project: ProjectEntity,
        tracks: List<TrackEntity>,
        audibleStart: AudibleMs,
    ): PlaybackArm? = withAudioIo(dispatchers, "PlaybackPort.startPlayback") {
        withIo(dispatchers, "prepare session transport capability") {
            sessionTransportGate.prepareForLiveSession(project.sampleRate)
        }
        PlaybackTransportSync.requirePreparedCapability(sessionTransportGate)
        val localTracks = tracks.filter { it.wavFilePath.isNotBlank() }
        val streamedTracks = tracks.filter { it.streamsSharedTake() }
        val startMs = PlaybackTransportSync.mixTransportMs(
            audibleStart,
            PlaybackTransportSync.effectiveOutputLatencyMsForUiSync(meter),
        )
        val spec = localSpec(project, localTracks, tracks, startMs)
        if (localTracks.isNotEmpty() && spec == null) return@withAudioIo null
        if (spec != null && !playback.startPlayback(spec)) return@withAudioIo null
        try {
            ProjectTrackStreams.player.play(
                project.id,
                streamedTracks,
                audibleStart.value,
                streamClock = localTracks.isEmpty() && streamedTracks.isNotEmpty(),
            )
        } catch (cancel: CancellationException) {
            if (spec != null) playback.stopPlayback()
            throw cancel
        } catch (error: Exception) {
            if (spec != null) playback.stopPlayback()
            ProjectTrackStreams.player.release()
            throw error
        }
        PlaybackArm(spec?.lanes?.map { it.trackId } ?: emptyList())
    }

    private fun localSpec(
        project: ProjectEntity,
        localTracks: List<TrackEntity>,
        allTracks: List<TrackEntity>,
        startMs: MixTransportMs,
    ): MultiPlaybackSpec? {
        if (localTracks.isEmpty()) return null
        val end = sessionPlaybackSchedulingEndMsForPlayback(allTracks)
        return project.toLiveEnginePlaybackSpec(localTracks, startMs)?.copy(sessionTimelineEndMs = end)
    }
}
