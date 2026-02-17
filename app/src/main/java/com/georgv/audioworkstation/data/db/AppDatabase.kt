package com.georgv.audioworkstation.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

@Database(
    entities = [
        ProjectEntity::class,
        TrackEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
}
