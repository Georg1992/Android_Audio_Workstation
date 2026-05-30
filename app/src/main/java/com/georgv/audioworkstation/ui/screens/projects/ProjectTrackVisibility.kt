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
    if (optimisticGains.isEmpty()) return withRecording
    return withRecording.map { track ->
        val gain = optimisticGains[track.id] ?: return@map track
        track.copy(gain = gain)
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
