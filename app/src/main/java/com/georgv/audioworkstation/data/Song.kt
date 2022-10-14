package com.georgv.audioworkstation.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(
    tableName = "songs"
)
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long,
    val filePath:String?,
    var inEditMode: Boolean,
    var songName:String?
    ) {

}