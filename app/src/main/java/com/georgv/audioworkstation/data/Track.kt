package com.georgv.audioworkstation.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "tracks",
    foreignKeys = [ForeignKey(
        entity = Song::class,
        parentColumns = ["id"],
        childColumns = ["songID"]
    )]
)
data class Track(
    @PrimaryKey(autoGenerate = true)
    val id:Long,
    var isRecording:Boolean?,
    var trackName:String,
    val pcmDir:String,
    val wavDir:String,
    val timeStampStart:Long,
    var timeStampStop:Long?,
    var duration:Long?,
    val songID: Long
    )