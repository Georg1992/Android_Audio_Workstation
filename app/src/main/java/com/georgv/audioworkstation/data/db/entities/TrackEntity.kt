package com.georgv.audioworkstation.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.georgv.audioworkstation.core.audio.ChannelMode

@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["projectId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("projectId")]
)
data class TrackEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String? = null,
    val channelMode: ChannelMode = ChannelMode.MONO,
    val gain: Float = 100f,
    val wavFilePath: String = "",
    val timeStampStart: Long = 0L,
    val timeStampStop: Long? = null,
    val duration: Long? = null,
    val isRecording: Boolean = false,
    val isLoop: Boolean = false,
    val isImported: Boolean = false,
    val position: Int = 0,

    // Collaboration plumbing — see [ProjectEntity] for the same set of fields.
    // Tracks additionally carry [contentHash] because audio payload is the
    // expensive thing to upload/download and we want a cheap equality check
    // before transferring bytes.
    val remoteUrl: String? = null,
    val contentHash: String? = null,
    val syncStatus: SyncStatus = SyncStatus.LOCAL,
    val ownerUserId: String? = null,
    val editLamport: Long = 0L
)
