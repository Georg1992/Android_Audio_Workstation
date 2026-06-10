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
     * Idle/base ruler length from **selected** persisted clips (plus recording rules below).
     * Unselected lanes do not extend or mark the base track. Empty selection → 0 until tracks are selected.
     * Loop regions do not shrink or grow this value.
     */
    val baseTimelineDurationMs: Long,
    /**
     * Global mix-scope layout extent (selected tracks). Per-lane waveforms use
     * [timelineLaneLocalLayoutDurationMs] in the track list.
     */
    val laneLayoutDurationMs: Long,
    /**
     * Live ruler length for the global scrubber and non-loop lane playheads.
     * May extend past [baseTimelineDurationMs] during looped playback past base,
     * or while recording grows past base.
     */
    val visibleTimelineDurationMs: Long,
)

fun buildProjectTimelineProjection(
    tracks: List<TrackEntity>,
    waveformStatesByTrackId: Map<String, WaveformState>,
    selectedTrackIds: Set<String>,
    activeRecording: ActiveRecordingTimelineClip?,
    playheadPositionMs: Long,
    extendVisibleTimelineForAllLoopedPlayback: Boolean,
    extendVisibleTimelineForRecording: Boolean,
    importProgressByTrackId: Map<String, Float> = emptyMap(),
): ProjectTimelineProjection {
    val persistedClips = projectTimelineClips(tracks, waveformStatesByTrackId, importProgressByTrackId)
    val scopeTrackIds =
        timelineScopeTrackIds(
            selectedTrackIds = selectedTrackIds,
            activeRecordingTrackId = activeRecording?.trackId,
        )
    val scopedPersistedClips = timelineScopeClips(persistedClips, scopeTrackIds)
    val recordingClip =
        activeRecording?.let { recording ->
            activeRecordingTimelineClip(
                recording = recording,
                furthestEndMs =
                    scopedPersistedClips.maxOfOrNull { timelineClipEffectiveTimelineEndMs(it) },
            )
        }
    val clips = timelineClipsWithActiveRecording(persistedClips, recordingClip)
    val scopedClips = timelineScopeClips(clips, scopeTrackIds)
    val baseTimelineDurationMs =
        timelineBaseDurationMsFromClips(scopedPersistedClips)
    val laneLayoutDurationMs =
        timelineLaneLayoutDurationMs(
            persistedClips = scopedPersistedClips,
            mergedClips = scopedClips,
        )
    val visibleTimelineDurationMs =
        visibleTimelineDurationMs(
            baseTimelineDurationMs = baseTimelineDurationMs,
            playheadPositionMs = playheadPositionMs,
            activeRecording = activeRecording,
            extendForAllLoopedPlayback = extendVisibleTimelineForAllLoopedPlayback,
            extendForRecording = extendVisibleTimelineForRecording,
        )
    val furthestScopedClipEndMs =
        scopedClips.maxOfOrNull { timelineClipEffectiveTimelineEndMs(it) } ?: 0L
    val clipsWithBase =
        clips.map { clip ->
            val endMs = timelineClipEffectiveTimelineEndMs(clip)
            clip.copy(
                isTimelineBase =
                    clip.laneId in scopeTrackIds &&
                        endMs == furthestScopedClipEndMs &&
                        furthestScopedClipEndMs > 0L,
            )
        }
    return ProjectTimelineProjection(
        clips = clipsWithBase,
        clipsByLaneId = clipsWithBase.associateBy { it.laneId },
        baseTimelineDurationMs = baseTimelineDurationMs,
        laneLayoutDurationMs = laneLayoutDurationMs,
        visibleTimelineDurationMs = visibleTimelineDurationMs,
    )
}

/** Clips that participate in global base timeline / base-track semantics for the current selection. */
fun timelineScopeClips(
    clips: List<TimelineClip>,
    scopeTrackIds: Set<String>,
): List<TimelineClip> {
    if (scopeTrackIds.isEmpty()) return emptyList()
    return clips.filter { clip -> clip.laneId in scopeTrackIds }
}

/** Selected lanes plus the active recording row (when present) define global timeline scope. */
fun timelineScopeTrackIds(
    selectedTrackIds: Set<String>,
    activeRecordingTrackId: String? = null,
): Set<String> =
    buildSet {
        addAll(selectedTrackIds)
        if (activeRecordingTrackId != null) {
            add(activeRecordingTrackId)
        }
    }

/** Base timeline from persisted clips only (no in-flight recording row, no playhead). */
fun timelineBaseDurationMsFromClips(clips: List<TimelineClip>): Long {
    val furthestClipEndMs =
        clips.maxOfOrNull { timelineClipEffectiveTimelineEndMs(it) } ?: 0L
    if (furthestClipEndMs <= 0L) return 0L
    return furthestClipEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

/**
 * Duration used to lay out lane clips. Extends past persisted base when a recording (or any merged
 * clip) starts or ends beyond existing audio so [timelineClipLayout] can place the clip.
 */
fun timelineLaneLayoutDurationMs(
    persistedClips: List<TimelineClip>,
    mergedClips: List<TimelineClip>,
): Long {
    val persistedBase = timelineBaseDurationMsFromClips(persistedClips)
    val mergedEndMs =
        mergedClips.maxOfOrNull { timelineClipEffectiveTimelineEndMs(it) } ?: 0L
    val layoutEndMs = maxOf(persistedBase, mergedEndMs)
    if (layoutEndMs <= 0L) return 0L
    return layoutEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
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
    } else if (extendForAllLoopedPlayback) {
        visible = max(visible, playheadMs.coerceAtLeast(baseTimelineDurationMs))
    }
    if (visible <= 0L) return 0L
    return visible.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

/**
 * Extends the visible ruler while any looping lane in the **active mix scope** is playing.
 */
fun shouldExtendVisibleTimelineForAllLoopedPlayback(
    playbackSessionActive: Boolean,
    selectedTrackIds: Set<String>,
    tracks: List<TrackEntity>,
): Boolean {
    if (!playbackSessionActive || selectedTrackIds.isEmpty()) return false
    return tracks.any { it.id in selectedTrackIds && it.isLoop }
}

/** Absolute session timeline end from persisted clips (no active recording row). */
fun sessionTimelineEndMsForTracks(tracks: List<TrackEntity>): Long {
    val furthestClipEndMs =
        tracks.maxOfOrNull { track -> track.effectiveTimelineEndMs() } ?: 0L
    return furthestClipEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

/** Offline mixdown always renders from timeline 0:00. */
const val MixdownTimelineStartMs = 0L

/**
 * Furthest idle timeline boundary for mixdown — matches selection-scoped
 * [ProjectTimelineProjection.baseTimelineDurationMs].
 */
fun mixdownTimelineEndMs(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
): Long =
    timelineBaseDurationMsFromClips(
        timelineScopeClips(
            projectTimelineClips(tracks, emptyMap(), emptyMap()),
            selectedTrackIds,
        ),
    )

/**
 * Open-ended native playback when any selected lane loops (mixed or all-loop sessions).
 */
fun hasOpenEndedPlaybackSession(tracks: List<TrackEntity>): Boolean = tracks.any { it.isLoop }

/** Allow starting/restarting playback at the base timeline end when any lane loops. */
fun playbackStartAllowedAtPlayhead(
    startPositionMs: Long,
    timelineBaseDurationMs: Long,
    tracks: List<TrackEntity>,
): Boolean {
    if (timelineBaseDurationMs <= 0L || startPositionMs < timelineBaseDurationMs) return true
    return hasOpenEndedPlaybackSession(tracks)
}

/**
 * Visual project-timeline session end for UI layout and offline mixdown bounds.
 * Live engine playback uses [sessionPlaybackSchedulingEndMsForPlayback] instead.
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

/**
 * When punch-recording into an existing track, keep the persisted clip (waveform + loop bounds)
 * and only mark it active. Brand-new takes still use the standalone active-recording clip.
 */
internal fun timelineClipsWithActiveRecording(
    persistedClips: List<TimelineClip>,
    recordingClip: TimelineClip?,
): List<TimelineClip> {
    if (recordingClip == null) return persistedClips
    val existing = persistedClips.find { it.laneId == recordingClip.laneId } ?: return persistedClips + recordingClip
    return persistedClips.map { clip ->
        if (clip.laneId == recordingClip.laneId) {
            clip.copy(isActiveRecording = true)
        } else {
            clip
        }
    }
}

/** Minimum visible clip width on the timeline while elapsed is still 0. */
fun layoutDurationMsForActiveRecording(elapsedMs: Long): Long =
    elapsedMs.coerceAtLeast(0L).let { elapsed ->
        if (elapsed > 0L) elapsed else 1L
    }
