package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.track.effectiveLoopEndMs
import com.georgv.audioworkstation.core.track.effectiveLoopStartMs
import com.georgv.audioworkstation.core.track.sourceDurationMs
import com.georgv.audioworkstation.data.db.entities.TrackEntity

/**
 * Whether a track should begin async hot-join when selected during an active session.
 *
 * Selected/green is audibility intent; this gate only decides if native lane prepare is useful.
 * Timeline audibility at commit/render still follows [isLaneAudibleAtPlayhead].
 */
fun shouldHotJoinTrackAtTransport(track: TrackEntity, transportMs: Long): Boolean {
    if (track.wavFilePath.isBlank()) return false
    if (track.isLoop) return true
    val clipStartMs = track.timelineStartOffsetMs.coerceAtLeast(0L)
    val clipDurationMs = track.sourceDurationMs()
    if (clipDurationMs <= 0L) return true
    return transportMs < clipStartMs + clipDurationMs
}

/** Loop + timeline metadata forwarded to native hot-join arm. */
data class HotJoinLaneSpec(
    val timelineClipStartMs: Long,
    val timelineClipDurationMs: Long,
    val loopEnabled: Boolean,
    val loopSourceStartMs: Long,
    val loopSourceEndMs: Long,
)

fun TrackEntity.toHotJoinLaneSpec(): HotJoinLaneSpec =
    HotJoinLaneSpec(
        timelineClipStartMs = timelineStartOffsetMs.coerceAtLeast(0L),
        timelineClipDurationMs = sourceDurationMs(),
        loopEnabled = isLoop,
        loopSourceStartMs = effectiveLoopStartMs(),
        loopSourceEndMs = effectiveLoopEndMs(),
)
