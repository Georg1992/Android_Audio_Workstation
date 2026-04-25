package com.georgv.audioworkstation.data.db

import androidx.room.TypeConverter
import com.georgv.audioworkstation.core.audio.ChannelMode
import com.georgv.audioworkstation.data.db.entities.SyncStatus

class AudioConfigConverters {

    @TypeConverter
    fun fromChannelMode(value: ChannelMode): String = value.name

    @TypeConverter
    fun toChannelMode(value: String): ChannelMode = ChannelMode.valueOf(value)

    @TypeConverter
    fun fromSyncStatus(value: SyncStatus): String = value.name

    @TypeConverter
    fun toSyncStatus(value: String): SyncStatus = SyncStatus.valueOf(value)
}
