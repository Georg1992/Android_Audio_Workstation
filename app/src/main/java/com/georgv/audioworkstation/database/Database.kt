package com.georgv.audioworkstation.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import kotlinx.coroutines.CoroutineScope

@Database(entities = [(Track::class), (Song::class)], version = 1)
abstract class SongDB : RoomDatabase(){
    abstract fun songDao(): SongDao
    abstract fun trackDao(): TrackDao

    companion object{
        private var sInstance: SongDB? = null
        @Synchronized
        fun get(context: Context,scope:CoroutineScope): SongDB {
            if (sInstance == null) {
                sInstance =
                    Room.databaseBuilder(context.applicationContext,
                        SongDB::class.java, "songs.db").build()
            }
            return sInstance!!
        }
    }
}