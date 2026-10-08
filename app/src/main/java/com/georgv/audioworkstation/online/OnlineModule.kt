package com.georgv.audioworkstation.online

import android.content.Context
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.online.network.HttpProjectShareApi
import com.georgv.audioworkstation.online.network.HttpTransport
import com.georgv.audioworkstation.online.network.JsonHttp
import com.georgv.audioworkstation.online.network.UrlConnectionHttpTransport
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class OnlineModule {
    @Binds
    @Singleton
    abstract fun bindAccountSessionStore(impl: DataStoreAccountSessionStore): AccountSessionStore

    @Binds
    @Singleton
    abstract fun bindCognitoRedirects(impl: ChannelCognitoRedirects): CognitoRedirects

    companion object {
        @Provides
        @Singleton
        fun provideHttpTransport(): HttpTransport = UrlConnectionHttpTransport(OnlineApiBaseUrl.VALUE)

        @Provides
        @Singleton
        fun provideJsonHttp(transport: HttpTransport): JsonHttp = JsonHttp(transport)

        @Provides
        @Singleton
        fun provideProjectShareApi(http: JsonHttp, dispatchers: AppDispatchers): ProjectShareApi =
            HttpProjectShareApi(http, dispatchers.io)

        @Provides
        @Singleton
        fun provideCognitoSignIn(
            @ApplicationContext context: Context,
            redirects: CognitoRedirects,
            dispatchers: AppDispatchers,
        ): CognitoSignIn = HostedCognitoSignIn.create(
            context = context,
            redirects = redirects,
            io = dispatchers.io,
            main = dispatchers.main,
        )
    }
}
