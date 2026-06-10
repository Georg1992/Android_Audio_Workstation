package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs

/**
 * Timeline placement rules for transport commands — not latency compensation.
 *
 * Loop start-at-zero and overdub recording clip placement live here so transport controllers
 * do not embed timeline policy inline.
 */
object TransportTimelinePolicy {
    /**
     * Timeline offset for a new recording clip at REC press.
     * Overdub backing uses engine arm position; record-only uses the scrubbed playhead.
     */
    fun recordingClipTimelineStartMs(
        playheadMs: Long,
        overdubPlaybackStartMs: Long?,
        hasOverdubBacking: Boolean,
    ): Long =
        if (hasOverdubBacking && overdubPlaybackStartMs != null) {
            overdubPlaybackStartMs
        } else {
            playheadMs
        }

    /** Loop playback always starts at 0 on the global loop timeline, not the scrubbed project position. */
    fun playbackStartPositionMsForTracks(
        scrubbedPlayheadMs: Long,
        timelineVisibleDurationMs: Long,
        tracks: List<TrackEntity>,
    ): AudibleMs {
        val startMs =
            if (tracks.any { it.isLoop }) {
                0L
            } else {
                timelinePlayheadClampedPositionMs(
                    scrubbedPlayheadMs,
                    timelineVisibleDurationMs,
                )
            }
        return AudibleMs(startMs)
    }
}
