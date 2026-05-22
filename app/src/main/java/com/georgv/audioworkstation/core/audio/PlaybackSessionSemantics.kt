package com.georgv.audioworkstation.core.audio

/**
 * Derived playback-session semantics (Clock.1).
 *
 * [selectedTrackIds] is UI audible intent only.
 * [sessionLaneTrackIds] lists every track loaded on a native lane (transport arm, loop, hot-join).
 * [sessionTrackIds] is the transport-start / loop-restart spec only (see [PlaybackSessionController]).
 */
fun audibleTrackIds(
    selectedTrackIds: Set<String>,
    sessionLaneTrackIds: Array<String?>,
): Set<String> =
    sessionLaneTrackIds
        .asSequence()
        .filterNotNull()
        .filter { it in selectedTrackIds }
        .toSet()

fun isTrackLoadedInSessionLane(
    trackId: String,
    sessionLaneTrackIds: Array<String?>,
): Boolean = sessionLaneTrackIds.any { it == trackId }
