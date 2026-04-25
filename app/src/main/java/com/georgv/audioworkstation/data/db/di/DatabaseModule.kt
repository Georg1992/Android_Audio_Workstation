package com.georgv.audioworkstation.data.db.di

import android.content.Context
import androidx.room.Room
import com.georgv.audioworkstation.data.db.AppDatabase
import com.georgv.audioworkstation.data.db.MIGRATION_3_4
import com.georgv.audioworkstation.data.db.MIGRATION_4_5
import com.georgv.audioworkstation.data.db.MIGRATION_5_6
import com.georgv.audioworkstation.data.db.MIGRATION_6_7
import com.georgv.audioworkstation.data.db.MIGRATION_7_8
import com.georgv.audioworkstation.data.db.dao.ProjectDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(
        @ApplicationContext context: Context
    ): AppDatabase =
        Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "audioworkstation.db"
        )
            .addMigrations(
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8
            )
            .build()

    @Provides
    fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()
}
