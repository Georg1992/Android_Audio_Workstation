package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.ActiveRecordingTimelineClip
import com.georgv.audioworkstation.ui.components.WaveformState

/** Base list for UI: reorder override, then Room; append optimistic recording only if that id is absent. */
internal fun visibleTracksWithRecordingOptimistic(
    projectTracksList: List<TrackEntity>,
    optimisticOrder: List<TrackEntity>?,
    optimisticRecording: TrackEntity?,
    optimisticGains: Map<String, Float> = emptyMap(),
    optimisticPans: Map<String, Float> = emptyMap(),
): List<TrackEntity> {
    val base = optimisticOrder ?: projectTracksList
    val withRecording =
        when {
            optimisticRecording == null -> base
            base.any { it.id == optimisticRecording.id } ->
                base.map { track ->
                    if (track.id == optimisticRecording.id) optimisticRecording else track
                }
            else -> base + optimisticRecording
        }
    val withGains =
        if (optimisticGains.isEmpty()) {
            withRecording
        } else {
            withRecording.map { track ->
                val gain = optimisticGains[track.id] ?: return@map track
                track.copy(gain = gain)
            }
        }
    if (optimisticPans.isEmpty()) return withGains
    return withGains.map { track ->
        val pan = optimisticPans[track.id] ?: return@map track
        track.copy(pan = pan)
    }
}

/**
 * Live recording clip for timeline projection. Active whenever [recordingTrackId] is set —
 * including play+record overdub startup before transport enters [TransportPlaybackPhase.Recording].
 */
internal fun activeRecordingTimelineClip(
    tracks: List<TrackEntity>,
    recordingTrackId: String?,
    playheadMs: Long,
): ActiveRecordingTimelineClip? {
    val recordingId = recordingTrackId ?: return null
    val recordingTrack = tracks.find { it.id == recordingId } ?: return null
    val startOffsetMs = recordingTrack.timelineStartOffsetMs.coerceAtLeast(0L)
    val elapsedMs = (playheadMs - startOffsetMs).coerceAtLeast(0L)
    return ActiveRecordingTimelineClip(
        trackId = recordingId,
        startOffsetMs = startOffsetMs,
        elapsedMs = elapsedMs,
    )
}

/** Lanes that show a moving playhead overlay (recording take or active playback session). */
internal fun trackLaneNeedsLivePlayhead(
    trackId: String,
    recordingTrackId: String?,
    playbackSessionActive: Boolean,
    sessionTrackIds: Set<String>,
): Boolean =
    trackId == recordingTrackId ||
        (playbackSessionActive && trackId in sessionTrackIds)
