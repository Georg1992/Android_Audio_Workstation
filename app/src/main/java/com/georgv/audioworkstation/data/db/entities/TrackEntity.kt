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
    val projectId: String,          // вместо songId
    val name: String? = null,
    val channelMode: ChannelMode = ChannelMode.MONO,
    val inputChannel: Int = 0,
    val gain: Float = 100f,
    val wavFilePath: String = "",
    val timeStampStart: Long = 0L,
    val timeStampStop: Long? = null,
    val duration: Long? = null,
    val isRecording: Boolean = false,
    val position: Int = 0
)
