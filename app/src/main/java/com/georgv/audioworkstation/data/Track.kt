package com.georgv.audioworkstation.data

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

open class Track() : RealmObject {
    @PrimaryKey
    var id: String = UUID.randomUUID().toString()
    var songId: String? = null
    var name:String? = null
    var volume:Float = 100F
    var wavFilePath: String = ""
    var isRecording:Boolean = false
    private var inEditMode: Boolean = false
    var timeStampStart:Long = 0
    var timeStampStop:Long? = null
    var duration:Long? = null

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as Track
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    fun areContentsTheSame(other: Track): Boolean {
        return name == other.name && wavFilePath == other.wavFilePath && inEditMode == other.inEditMode
    }
}

/**
 * Thread-safe data class for UI operations
 * Used for copying Realm Track objects to avoid threading issues
 */
data class TrackData(
    val id: String,
    val songId: String?,
    val name: String?,
    val volume: Float,
    val wavFilePath: String,
    val isRecording: Boolean,
    val timeStampStart: Long,
    val timeStampStop: Long?,
    val duration: Long?
)

/**
 * Extension function to convert Realm Track to thread-safe TrackData
 */
fun Track.toTrackData(): TrackData {
    return TrackData(
        id = this.id,
        songId = this.songId,
        name = this.name,
        volume = this.volume,
        wavFilePath = this.wavFilePath,
        isRecording = this.isRecording,
        timeStampStart = this.timeStampStart,
        timeStampStop = this.timeStampStop,
        duration = this.duration
    )
}


