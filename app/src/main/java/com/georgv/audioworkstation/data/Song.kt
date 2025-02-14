package com.georgv.audioworkstation.data

import io.realm.kotlin.types.RealmObject
import io.realm.kotlin.types.annotations.PrimaryKey
import java.util.UUID

open class Song() : RealmObject {
    @PrimaryKey
    var id: String = UUID.randomUUID().toString()
    var name: String? = null
    var wavFilePath: String? = null
    var inEditMode: Boolean = false

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




