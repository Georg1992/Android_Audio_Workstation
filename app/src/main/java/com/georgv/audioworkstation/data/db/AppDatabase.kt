package com.georgv.audioworkstation.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

@Database(
    entities = [
        ProjectEntity::class,
        TrackEntity::class
    ],
    version = 8,
    exportSchema = false
)
@TypeConverters(AudioConfigConverters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
}
