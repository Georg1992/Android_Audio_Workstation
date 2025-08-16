package com.georgv.audioworkstation.data

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

open class Song() : RealmObject {
    @PrimaryKey
    var id: String = UUID.randomUUID().toString()
    var name: String? = null
    var wavFilePath: String? = null
    private var inEditMode: Boolean = false

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other == null || javaClass != other.javaClass) return false
        other as Song
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }

    fun areContentsTheSame(other: Song): Boolean {
        return name == other.name && wavFilePath == other.wavFilePath && inEditMode == other.inEditMode
    }
}

/**
 * Thread-safe data class for UI operations
 * Used for copying Realm Song objects to avoid threading issues
 */
data class SongData(
    val id: String,
    val name: String?,
    val wavFilePath: String?
)

/**
 * Extension function to convert Realm Song to thread-safe SongData
 */
fun Song.toSongData(): SongData {
    return SongData(
        id = this.id,
        name = this.name,
        wavFilePath = this.wavFilePath
    )
}




