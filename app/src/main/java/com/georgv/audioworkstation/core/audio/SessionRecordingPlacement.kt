package com.georgv.audioworkstation.core.audio

/**
 * Resolves native playback clip start after stop.
 *
 * [TrackEntity.timelineStartOffsetMs] stores capture placement for metadata. Native scheduling
 * and timeline UI layout use [TrackEntity.playbackTimelineClipStartMs].
 */
object SessionRecordingPlacement {
    fun resolveTimelineStartOffsetMs(
        firstSampleTransportPositionMs: Long,
        sessionPerceivedPlaybackOffsetMs: Long,
        overdubBackingArmMs: Long?,
    ): Long {
        require(firstSampleTransportPositionMs >= 0L) {
            "Capture placement transport ms is required"
        }
        if (overdubBackingArmMs == null || sessionPerceivedPlaybackOffsetMs < 0L) {
            return firstSampleTransportPositionMs
        }
        return (firstSampleTransportPositionMs - sessionPerceivedPlaybackOffsetMs)
            .coerceAtLeast(overdubBackingArmMs)
    }
}
