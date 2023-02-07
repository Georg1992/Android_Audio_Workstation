package com.georgv.audioworkstation.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.georgv.audioworkstation.customHandlers.TypeConverter

@kotlinx.parcelize.Parcelize
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
    @TypeConverters(TypeConverter::class)
    var equalizer: String?,
    var compressor: String?,
    var reverb: String?
    ):Parcelable

