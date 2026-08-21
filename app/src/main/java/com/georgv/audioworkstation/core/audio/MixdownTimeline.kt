package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.timeline.TimelineMinimumBaseDurationMs
import com.georgv.audioworkstation.core.track.hasTimelineClip
import com.georgv.audioworkstation.core.track.timelineClipDurationMs
import com.georgv.audioworkstation.data.db.entities.TrackEntity

/**
 * Furthest idle timeline boundary for mixdown — matches selection-scoped
 * persisted clip ends (waveforms ignored). Loop regions do not shrink this value.
 */
fun mixdownTimelineEndMs(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
): Long {
    val furthestClipEndMs =
        tracks
            .asSequence()
            .filter { track -> track.id in selectedTrackIds }
            .mapNotNull { track -> mixdownClipEndMs(track) }
            .maxOrNull() ?: 0L
    if (furthestClipEndMs <= 0L) return 0L
    return furthestClipEndMs.coerceAtLeast(TimelineMinimumBaseDurationMs)
}

private fun mixdownClipEndMs(track: TrackEntity): Long? {
    if (!track.hasTimelineClip()) return null
    track.duration?.takeIf { it > 0L } ?: return null
    if (track.isRecording &&
        track.importStatus == TrackImportStatus.READY &&
        track.wavFilePath.isBlank()
    ) {
        return null
    }
    return track.playbackTimelineClipStartMs() + track.timelineClipDurationMs().coerceAtLeast(0L)
}

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
