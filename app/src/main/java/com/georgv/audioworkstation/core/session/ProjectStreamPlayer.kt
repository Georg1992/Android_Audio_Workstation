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

    /** Streaming form of [com.georgv.audioworkstation.core.audio.PlaybackPort.restartAt]. */
    fun restartAt(positionMs: Long)

    fun useStreamClock(enabled: Boolean)

    fun stop()

    fun positionMs(): Long

    fun ownsClock(): Boolean

    fun consumeClock(onFailure: (Exception) -> Unit): Boolean

    fun release()
}
