package com.georgv.audioworkstation.data

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.parcelize.Parcelize

@Parcelize
@Entity(
    tableName = "songs"
)
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    val filePath:String?,
    val wavFilePath: String?,
    var inEditMode: Boolean,
    var songName:String?
    ):Parcelable {

}