package com.georgv.audioworkstation.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sampleRate: Int = 48_000,
    val fileBitDepth: Int = 16,

    // Collaboration plumbing — wired into schema now so the upcoming online
    // collaboration work doesn't require another migration. None of these
    // fields are consumed yet; they default to "LOCAL" / null on every insert.
    val remoteUrl: String? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL,
    val ownerUserId: String? = null,
    /**
     * Lamport-style logical clock for ordering edits made on different
     * devices. Bumped by future sync code on every meaningful project edit.
     */
    val editLamport: Long = 0L
)
