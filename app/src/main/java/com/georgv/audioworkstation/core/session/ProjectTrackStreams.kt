package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.data.db.entities.TrackEntity

internal object ProjectTrackStreams {
    @Volatile
    var player: ProjectStreamPlayer = InactiveProjectStreamPlayer
}

private object InactiveProjectStreamPlayer : ProjectStreamPlayer {
    override suspend fun play(
        projectId: String,
        tracks: List<TrackEntity>,
        positionMs: Long,
        streamClock: Boolean,
    ) {
        if (tracks.isEmpty()) return
        error("shared tracks are not available")
    }

    override fun sameTracks(tracks: List<TrackEntity>): Boolean = false

    override fun seekTo(positionMs: Long) = Unit

    override fun useStreamClock(enabled: Boolean) = Unit

    override fun stop() = Unit

    override fun positionMs(): Long = 0L

    override fun ownsClock(): Boolean = false

    override fun consumeClock(onFailure: (Exception) -> Unit): Boolean = false

    override fun release() = Unit
}
