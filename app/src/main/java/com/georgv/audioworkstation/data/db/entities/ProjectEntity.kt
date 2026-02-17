package com.georgv.audioworkstation.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val lastOpened: Long = System.currentTimeMillis(),
    val bpm: Int = 120,
    val sampleRate: Int = 44100,
)

