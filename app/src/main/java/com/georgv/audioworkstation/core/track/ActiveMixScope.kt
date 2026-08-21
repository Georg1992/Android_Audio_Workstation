package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.core.audio.MixTransportMs
import com.georgv.audioworkstation.core.audio.sessionPlaybackSchedulingEndMsForPlayback
import com.georgv.audioworkstation.data.db.entities.TrackEntity

/**
 * Active mix scope: selected tracks plus the active recording row (when present).
 * Timeline, playback bounds, and mixdown all derive from this set.
 */
fun activeMixScopeTrackIds(
    selectedTrackIds: Set<String>,
    activeRecordingTrackId: String? = null,
): Set<String> =
    buildSet {
        addAll(selectedTrackIds)
        if (activeRecordingTrackId != null) {
            add(activeRecordingTrackId)
        }
    }

/** Playable lanes in scope for playback / mixdown (selection only; recording row excluded). */
fun activeMixScopePlayableTracks(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
): List<TrackEntity> = selectedPlayableTracks(tracks, selectedTrackIds)

/**
 * Backing lanes armed during overdub: selected playable tracks excluding the recording row.
 */
fun activeMixScopeOverdubPlaybackTracks(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
    recordingTrackId: String?,
): List<TrackEntity> {
    val playable = activeMixScopePlayableTracks(tracks, selectedTrackIds)
    if (recordingTrackId == null) return playable
    return playable.filter { it.id != recordingTrackId }
}

/**
 * True when non-loop scoped playback must stop because mix transport reached scheduling session end.
 *
 * Compares raw [mixTransportMs] to [sessionPlaybackSchedulingEndMsForPlayback] — not visual timeline end.
 */
fun playbackMustStopAtScopeEnd(
    mixTransportMs: MixTransportMs,
    scopePlayableTracks: List<TrackEntity>,
): Boolean {
    val sessionEndMs = sessionPlaybackSchedulingEndMsForPlayback(scopePlayableTracks)
    return sessionEndMs > 0L && mixTransportMs.value >= sessionEndMs
}

/** Mix transport position after scope-driven transport stop. */
fun playheadMsAfterScopeStop(
    mixTransportMs: MixTransportMs,
    scopePlayableTracks: List<TrackEntity>,
    selectionEmpty: Boolean,
): MixTransportMs {
    if (selectionEmpty) return MixTransportMs(0L)
    val sessionEndMs = sessionPlaybackSchedulingEndMsForPlayback(scopePlayableTracks)
    if (sessionEndMs > 0L && mixTransportMs.value >= sessionEndMs) {
        return MixTransportMs(sessionEndMs)
    }
    return mixTransportMs
}

/**
 * When scope changes, clamp in-scope loop regions to valid source bounds.
 * If a region cannot satisfy minimum length, loop mode is disabled.
 */
fun reconcileLoopRegionForScope(track: TrackEntity): TrackEntity {
    if (!track.isLoop) return track
    val sourceDurationMs = track.sourceDurationMs()
    if (sourceDurationMs <= 0L) return track.copy(isLoop = false)
    val (startMs, endMs) =
        clampLoopRegionMs(
            loopStartMs = track.loopStartMs,
            loopEndMs = track.loopEndMs ?: sourceDurationMs,
            sourceDurationMs = sourceDurationMs,
        )
    if (endMs - startMs < TrackLoopRegionMinLengthMs) {
        return track.copy(isLoop = false)
    }
    return track.copy(loopStartMs = startMs, loopEndMs = endMs)
}

fun reconcileInScopeLoopRegions(
    tracks: List<TrackEntity>,
    scopeTrackIds: Set<String>,
): List<TrackEntity> =
    tracks.map { track ->
        if (track.id !in scopeTrackIds) track else reconcileLoopRegionForScope(track)
    }
