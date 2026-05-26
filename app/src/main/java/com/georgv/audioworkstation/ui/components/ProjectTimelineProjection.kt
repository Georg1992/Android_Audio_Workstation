package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.core.track.effectiveTimelineEndMs
import kotlin.math.max

/** In-progress take on the shared project timeline (not yet a finalized playable clip). */
data class ActiveRecordingTimelineClip(
    val trackId: String,
    val startOffsetMs: Long,
    val elapsedMs: Long,
)

data class ProjectTimelineProjection(
    val clips: List<TimelineClip>,
    val clipsByLaneId: Map<String, TimelineClip>,
    /**
     * Idle/base ruler length from persisted clip placement + full source duration only.
     * Loop regions do not shrink or grow this value.
     */
    val baseTimelineDurationMs: Long,
    /**
     * Live ruler length for the global scrubber and non-loop lane playheads.
     * May extend past [baseTimelineDurationMs] only during all-looped playback past base,
     * or while recording grows past base.
     */
    val visibleTimelineDurationMs: Long,
) {
    /** @deprecated Use [visibleTimelineDurationMs]; kept for gradual migration in tests. */
    val timelineDurationMs: Long get() = visibleTimelineDurationMs
}

fun buildProjectTimelineProjection(
    tracks: List<TrackEntity>,
    waveformStatesByTrackId: Map<String, WaveformState>,
    activeRecording: ActiveRecordingTimelineClip?,
    playheadPositionMs: Long,
    extendVisibleTimelineForAllLoopedPlayback: Boolean,
    extendVisibleTimelineForRecording: Boolean,
): ProjectTimelineProjection {
    val persistedClips = projectTimelineClips(tracks, waveformStatesByTrackId)
    val recordingClip =
        activeRecording?.let { recording ->
            activeRecordingTimelineClip(
                recording = recording,
                furthestEndMs = persistedClips.maxOfOrNull { timelineClipEffectiveTimelineEndMs(it) },
            )
        }
    val clips =
        if (recordingClip == null) {
            persistedClips
        } else {
            persistedClips + recordingClip
        }
    val baseTimelineDurationMs =
        timelineBaseDurationMsFromClips(persistedClips)
    val visibleTimelineDurationMs =
        visibleTimelineDurationMs(
            baseTimelineDurationMs = baseTimelineDurationMs,
            playheadPositionMs = playheadPositionMs,
            activeRecording = activeRecording,
            extendForAllLoopedPlayback = extendVisibleTimelineForAllLoopedPlayback,
            extendForRecording = extendVisibleTimelineForRecording,
        )
    val furthestClipEndMs =
        clips.maxOfOrNull { timelineClipEffectiveTimelineEndMs(it) } ?: 0L
    val clipsWithBase =
        clips.map { clip ->
            val endMs = timelineClipEffectiveTimelineEndMs(clip)
            clip.copy(isTimelineBase = endMs == furthestClipEndMs && furthestClipEndMs > 0L)
        }
    return ProjectTimelineProjection(
        clips = clipsWithBase,
        clipsByLaneId = clipsWithBase.associateBy { it.laneId },
        baseTimelineDurationMs = baseTimelineDurationMs,
        visibleTimelineDurationMs = visibleTimelineDurationMs,
    )
}

/** Base timeline from persisted clips only (no in-flight recording row, no playhead). */
fun timelineBaseDurationMsFromClips(clips: List<TimelineClip>): Long {
    val furthestClipEndMs =
        clips.maxOfOrNull { timelineClipEffectiveTimelineEndMs(it) } ?: 0L
    if (furthestClipEndMs <= 0L) return 0L
    return furthestClipEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

fun visibleTimelineDurationMs(
    baseTimelineDurationMs: Long,
    playheadPositionMs: Long,
    activeRecording: ActiveRecordingTimelineClip?,
    extendForAllLoopedPlayback: Boolean,
    extendForRecording: Boolean,
): Long {
    val recordingEndMs =
        activeRecording?.let { recording ->
            recording.startOffsetMs.coerceAtLeast(0L) +
                recording.elapsedMs.coerceAtLeast(0L)
        } ?: 0L
    var visible = max(baseTimelineDurationMs, recordingEndMs)
    val playheadMs = playheadPositionMs.coerceAtLeast(0L)
    if (extendForRecording) {
        visible = max(visible, playheadMs)
    } else if (extendForAllLoopedPlayback && playheadMs > baseTimelineDurationMs) {
        visible = max(visible, playheadMs)
    }
    if (visible <= 0L) return 0L
    return visible.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

fun shouldExtendVisibleTimelineForAllLoopedPlayback(
    playbackSessionActive: Boolean,
    sessionTrackIds: Set<String>,
    tracks: List<TrackEntity>,
): Boolean {
    if (!playbackSessionActive || sessionTrackIds.isEmpty()) return false
    val activeTracks = tracks.filter { it.id in sessionTrackIds }
    return activeTracks.isNotEmpty() && activeTracks.all { it.isLoop }
}

/** Absolute session timeline end from persisted clips (no active recording row). */
fun sessionTimelineEndMsForTracks(tracks: List<TrackEntity>): Long {
    val furthestClipEndMs =
        tracks.maxOfOrNull { track -> track.effectiveTimelineEndMs() } ?: 0L
    return furthestClipEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

/**
 * Native playback session end. Loop-enabled lanes wrap indefinitely in the engine, so session
 * completion must not be bound to the base timeline when any loop track is playing.
 */
fun sessionTimelineEndMsForPlayback(tracks: List<TrackEntity>): Long {
    if (tracks.any { it.isLoop }) return 0L
    return sessionTimelineEndMsForTracks(tracks)
}

private fun activeRecordingTimelineClip(
    recording: ActiveRecordingTimelineClip,
    furthestEndMs: Long?,
): TimelineClip {
    val startOffsetMs = recording.startOffsetMs.coerceAtLeast(0L)
    val durationMs = layoutDurationMsForActiveRecording(recording.elapsedMs)
    val endMs = timelineClipEndMs(startOffsetMs, durationMs)
    val baseEnd = max(furthestEndMs ?: 0L, endMs)
    return TimelineClip(
        clipId = recording.trackId,
        laneId = recording.trackId,
        startOffsetMs = startOffsetMs,
        durationMs = durationMs,
        waveformState = WaveformState.NoWaveform,
        isTimelineBase = endMs == baseEnd && baseEnd > 0L,
        formattedDuration = formatTimelineDuration(recording.elapsedMs.coerceAtLeast(0L)),
        isActiveRecording = true,
        effectiveStartMs = 0L,
        effectiveEndMs = durationMs,
    )
}

/** Minimum visible clip width on the timeline while elapsed is still 0. */
fun layoutDurationMsForActiveRecording(elapsedMs: Long): Long =
    elapsedMs.coerceAtLeast(0L).let { elapsed ->
        if (elapsed > 0L) elapsed else 1L
    }
