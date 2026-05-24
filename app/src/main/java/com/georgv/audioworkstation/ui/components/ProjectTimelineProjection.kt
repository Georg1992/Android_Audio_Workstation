package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.data.db.entities.TrackEntity
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
    /** Shared ruler/waveform duration: max(clip ends, active recording end, playhead). */
    val timelineDurationMs: Long,
)

fun buildProjectTimelineProjection(
    tracks: List<TrackEntity>,
    waveformStatesByTrackId: Map<String, WaveformState>,
    activeRecording: ActiveRecordingTimelineClip?,
    playheadPositionMs: Long,
): ProjectTimelineProjection {
    val persistedClips = projectTimelineClips(tracks, waveformStatesByTrackId)
    val recordingClip =
        activeRecording?.let { recording ->
            activeRecordingTimelineClip(
                recording = recording,
                furthestEndMs = persistedClips.maxOfOrNull { timelineClipEndMs(it.startOffsetMs, it.durationMs) },
            )
        }
    val clips =
        if (recordingClip == null) {
            persistedClips
        } else {
            persistedClips + recordingClip
        }
    val furthestClipEndMs =
        clips.maxOfOrNull { timelineClipEndMs(it.startOffsetMs, it.durationMs) } ?: 0L
    val timelineDurationMs =
        timelineDurationMs(
            furthestClipEndMs = furthestClipEndMs,
            playheadPositionMs = playheadPositionMs,
        )
    val clipsWithBase =
        clips.map { clip ->
            val endMs = timelineClipEndMs(clip.startOffsetMs, clip.durationMs)
            clip.copy(isTimelineBase = endMs == furthestClipEndMs && furthestClipEndMs > 0L)
        }
    return ProjectTimelineProjection(
        clips = clipsWithBase,
        clipsByLaneId = clipsWithBase.associateBy { it.laneId },
        timelineDurationMs = timelineDurationMs,
    )
}

fun timelineDurationMs(
    furthestClipEndMs: Long,
    playheadPositionMs: Long,
): Long {
    if (furthestClipEndMs <= 0L) return 0L
    return max(
        furthestClipEndMs,
        playheadPositionMs.coerceAtLeast(0L),
    ).coerceAtLeast(TimelineMinimumBaseDurationMs)
}

/** Absolute session timeline end from persisted clips (no active recording row). */
fun sessionTimelineEndMsForTracks(tracks: List<TrackEntity>): Long {
    val furthestClipEndMs =
        tracks.maxOfOrNull { track ->
            val durationMs = track.duration ?: 0L
            timelineClipEndMs(track.timelineStartOffsetMs.coerceAtLeast(0L), durationMs)
        } ?: 0L
    return furthestClipEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
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
    )
}

/** Minimum visible clip width on the timeline while elapsed is still 0. */
fun layoutDurationMsForActiveRecording(elapsedMs: Long): Long =
    elapsedMs.coerceAtLeast(0L).let { elapsed ->
        if (elapsed > 0L) elapsed else 1L
    }
