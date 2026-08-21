package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.track.timelineClipDurationMs
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.timeline.TimelineMinimumBaseDurationMs

/**
 * Engine scheduling timeline — clip starts and session bounds for native playback.
 *
 * Uses [TrackEntity.playbackTimelineClipStartMs], not visual [TrackEntity.timelineStartOffsetMs].
 */
enum class TimelineClipStartSource {
    /** Visual/metadata placement on the project timeline. */
    VisualPlacement,
    /** Native playback scheduling (overdub sync correction applied). */
    PlaybackScheduling,
}

fun TrackEntity.timelineClipStartMsFor(source: TimelineClipStartSource): Long =
    when (source) {
        TimelineClipStartSource.VisualPlacement -> timelineStartOffsetMs.coerceAtLeast(0L)
        TimelineClipStartSource.PlaybackScheduling -> playbackTimelineClipStartMs()
    }

/** Absolute mix-transport end from playback scheduling placement. */
fun TrackEntity.effectivePlaybackSchedulingEndMs(): Long =
    playbackTimelineClipStartMs() + timelineClipDurationMs()

/** Furthest playback-scheduling clip end for scoped tracks (engine session bounds). */
fun sessionPlaybackSchedulingEndMsForTracks(tracks: List<TrackEntity>): Long {
    val furthestEndMs =
        tracks.maxOfOrNull { track -> track.effectivePlaybackSchedulingEndMs() } ?: 0L
    return furthestEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

/** Engine session end; zero when any scoped lane loops (native runs until manual stop). */
fun sessionPlaybackSchedulingEndMsForPlayback(tracks: List<TrackEntity>): Long {
    if (tracks.any { it.isLoop }) return 0L
    return sessionPlaybackSchedulingEndMsForTracks(tracks)
}

/** Live engine playback spec — scheduling clip starts and session end in mix coordinates. */
fun ProjectEntity.toLiveEnginePlaybackSpec(
    tracks: List<TrackEntity>,
    startPositionMs: MixTransportMs,
    sessionTimelineEndMs: Long = sessionPlaybackSchedulingEndMsForPlayback(tracks),
): MultiPlaybackSpec? =
    toMultiPlaybackSpec(
        tracks = tracks,
        clipStartSource = TimelineClipStartSource.PlaybackScheduling,
    )?.copy(
        startPositionMs = startPositionMs.value,
        sessionTimelineEndMs = sessionTimelineEndMs,
    )

/** Offline / visual timeline render — clip starts at visual placement, not overdub scheduling correction. */
fun ProjectEntity.toVisualTimelinePlaybackSpec(tracks: List<TrackEntity>): MultiPlaybackSpec? =
    toMultiPlaybackSpec(
        tracks = tracks,
        clipStartSource = TimelineClipStartSource.VisualPlacement,
    )
