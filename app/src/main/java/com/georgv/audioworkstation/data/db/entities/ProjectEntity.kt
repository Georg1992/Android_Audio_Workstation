package com.georgv.audioworkstation.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sampleRate: Int = 48_000,
    val fileBitDepth: Int = 16
)
