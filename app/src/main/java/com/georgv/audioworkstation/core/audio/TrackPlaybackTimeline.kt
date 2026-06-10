package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** Timeline clip start (ms) for native playback scheduling (may differ from visual [TrackEntity.timelineStartOffsetMs]). */
fun TrackEntity.playbackTimelineClipStartMs(): Long {
    if (overdubPlaybackSyncOffsetMs < 0L) {
        return timelineStartOffsetMs.coerceAtLeast(0L)
    }
    return SessionRecordingPlacement.resolveTimelineStartOffsetMs(
        firstSampleTransportPositionMs = timelineStartOffsetMs,
        sessionPerceivedPlaybackOffsetMs = overdubPlaybackSyncOffsetMs,
        overdubBackingArmMs = overdubBackingArmMs,
    )
}
