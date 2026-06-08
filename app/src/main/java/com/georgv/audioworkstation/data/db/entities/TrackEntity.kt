package com.georgv.audioworkstation.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.core.audio.TrackImportStatus

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
    /** PCM channel count in [wavFilePath] (1 = mono, 2 = stereo). */
    val channelCount: Int = 1,
    val gain: Float = 100f,
    /** Stereo pan -1 (full left) .. +1 (full right); 0 = center. */
    val pan: Float = 0f,
    val wavFilePath: String = "",
    val timeStampStart: Long = 0L,
    val timeStampStop: Long? = null,
    val duration: Long? = null,
    val isRecording: Boolean = false,
    val isLoop: Boolean = false,
    /** Track-local loop region start (ms from WAV start). Persisted while loop is off. */
    val loopStartMs: Long = 0L,
    /** Track-local loop region end (ms from WAV start); null = full [duration]. */
    val loopEndMs: Long? = null,
    val isImported: Boolean = false,
    val importStatus: TrackImportStatus = TrackImportStatus.READY,
    val position: Int = 0,
    /** Timeline position where this clip begins on the project base ruler (ms). */
    val timelineStartOffsetMs: Long = 0L,
    /** Non-destructive trim start within the source WAV (ms from file start). */
    val trimStartMs: Long = 0L,
    /** Non-destructive trim end within the source WAV; null = full [duration]. */
    val trimEndMs: Long? = null,

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
