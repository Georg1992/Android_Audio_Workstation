package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.online.network.UrlConnectionHttpTransport
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
        fun provideCognitoAccounts(dispatchers: AppDispatchers): CognitoAccounts =
            ApiCognitoAccounts(
                transport = UrlConnectionHttpTransport(CognitoConfig.ENDPOINT),
                io = dispatchers.io,
            )
    }
}
