package com.georgv.audioworkstation.data.db

import androidx.room.TypeConverter
import com.georgv.audioworkstation.core.audio.ChannelMode

class AudioConfigConverters {

    @TypeConverter
    fun fromChannelMode(value: ChannelMode): String = value.name

    @TypeConverter
    fun toChannelMode(value: String): ChannelMode = ChannelMode.valueOf(value)
}
