package com.georgv.audioworkstation.core.session

import com.georgv.audioworkstation.data.db.entities.TrackEntity

internal interface ProjectStreamPlayer {
    suspend fun play(
        projectId: String,
        tracks: List<TrackEntity>,
        positionMs: Long,
        streamClock: Boolean,
    )

    fun sameTracks(tracks: List<TrackEntity>): Boolean

    fun seekTo(positionMs: Long)

    fun useStreamClock(enabled: Boolean)

    fun stop()

    fun positionMs(): Long

    fun ownsClock(): Boolean

    fun consumeClock(onFailure: (Exception) -> Unit): Boolean

    fun release()
}
