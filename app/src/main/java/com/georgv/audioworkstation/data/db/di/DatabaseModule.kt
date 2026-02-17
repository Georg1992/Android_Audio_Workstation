package com.georgv.audioworkstation.data.db.di

import android.content.Context
import androidx.room.Room
import com.georgv.audioworkstation.data.db.AppDatabase
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
            .fallbackToDestructiveMigration() // тебе ок пока
            .build()

    @Provides
    fun provideProjectDao(db: AppDatabase): ProjectDao = db.projectDao()
}


