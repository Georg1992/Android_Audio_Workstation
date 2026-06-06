package com.georgv.audioworkstation.di

import com.georgv.audioworkstation.data.repository.ProjectRepositoryDiagnostics
import com.georgv.audioworkstation.ui.diagnostics.DebugProjectRepositoryDiagnostics
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindProjectRepositoryDiagnostics(
        impl: DebugProjectRepositoryDiagnostics,
    ): ProjectRepositoryDiagnostics
}
