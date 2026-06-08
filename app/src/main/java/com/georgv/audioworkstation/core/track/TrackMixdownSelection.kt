package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** Selected lanes for mixdown/playback in [tracks] list order (typically position ASC). */
fun selectedPlayableTracks(
    tracks: List<TrackEntity>,
    selectedTrackIds: Set<String>,
): List<TrackEntity> {
    if (selectedTrackIds.isEmpty()) return emptyList()
    return tracks.filter { track ->
        track.id in selectedTrackIds && track.hasPersistedPlayableAudio()
    }
}
