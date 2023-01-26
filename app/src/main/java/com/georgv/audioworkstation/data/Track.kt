package com.georgv.audioworkstation.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlinx.android.parcel.Parcelize

@Parcelize
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
    var volume:Float,
    val wavDir:String,
    val timeStampStart:Long,
    var timeStampStop:Long?,
    var duration:Long?,
    val songID: Long,
    val effects: String?
    ):Parcelable

