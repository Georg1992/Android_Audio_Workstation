package com.georgv.audioworkstation.online

import android.content.Context
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.online.network.HttpAccountApi
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

    companion object {
        @Provides
        @Singleton
        fun provideHttpTransport(): HttpTransport = UrlConnectionHttpTransport(OnlineApiBaseUrl.VALUE)

        @Provides
        @Singleton
        fun provideJsonHttp(transport: HttpTransport): JsonHttp = JsonHttp(transport)

        @Provides
        @Singleton
        fun provideAccountApi(http: JsonHttp, dispatchers: AppDispatchers): AccountApi =
            HttpAccountApi(http, dispatchers.io)

        @Provides
        @Singleton
        fun provideProjectShareApi(http: JsonHttp, dispatchers: AppDispatchers): ProjectShareApi =
            HttpProjectShareApi(http, dispatchers.io)

        @Provides
        @Singleton
        fun provideGmailSignIn(@ApplicationContext context: Context, dispatchers: AppDispatchers): GmailSignIn =
            LoopbackGmailSignIn(
                context = context,
                clientId = context.getString(R.string.google_web_client_id),
                clientSecret = context.getString(R.string.google_oauth_client_secret),
                io = dispatchers.io,
                main = dispatchers.main,
            )
    }
}
