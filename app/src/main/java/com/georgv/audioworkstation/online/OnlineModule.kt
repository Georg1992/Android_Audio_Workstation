package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnlineModule {
    @Binds
    @Singleton
    abstract fun bindAccountSessionStore(impl: DataStoreAccountSessionStore): AccountSessionStore

    companion object {
        @Provides
        @Singleton
        fun provideOnlineApi(dispatchers: AppDispatchers): OnlineApi =
            HttpOnlineApi(OnlineApiBaseUrl.VALUE, dispatchers.io)
    }
}
