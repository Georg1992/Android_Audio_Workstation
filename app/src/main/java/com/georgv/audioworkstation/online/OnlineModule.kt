package com.georgv.audioworkstation.online

import android.content.Context
import com.georgv.audioworkstation.core.coroutines.AppDispatchers
import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.network.UrlConnectionHttpTransport
import com.georgv.audioworkstation.online.share.AppCardCache
import com.georgv.audioworkstation.online.share.AppCardImageStore
import com.georgv.audioworkstation.online.share.CardCache
import com.georgv.audioworkstation.online.share.CardImageStore
import com.georgv.audioworkstation.online.share.CardMedia
import com.georgv.audioworkstation.online.share.HttpCardMedia
import com.georgv.audioworkstation.online.share.HttpProjectShare
import com.georgv.audioworkstation.online.share.HttpSharedProjects
import com.georgv.audioworkstation.online.share.ProjectShare
import com.georgv.audioworkstation.online.share.ShareConfig
import com.georgv.audioworkstation.online.share.SharedProjects
import com.georgv.audioworkstation.online.share.UrlConnectionShareObjects
import com.georgv.audioworkstation.ui.screens.community.CardPlayback
import com.georgv.audioworkstation.ui.screens.community.LocalProjectLookup
import com.georgv.audioworkstation.ui.screens.community.MediaCardPlayback
import com.georgv.audioworkstation.ui.screens.community.RoomLocalProjects
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

    @Binds
    @Singleton
    abstract fun bindCardPlayback(impl: MediaCardPlayback): CardPlayback

    @Binds
    @Singleton
    abstract fun bindCardCache(impl: AppCardCache): CardCache

    @Binds
    @Singleton
    abstract fun bindLocalProjects(impl: RoomLocalProjects): LocalProjectLookup

    @Binds
    @Singleton
    abstract fun bindCardImages(impl: AppCardImageStore): CardImageStore

    companion object {
        @Provides
        @Singleton
        fun provideGoogleSignIn(
            @ApplicationContext context: Context,
            redirects: CognitoRedirects,
            dispatchers: AppDispatchers,
        ): GoogleCognitoSignIn = GoogleCognitoSignIn.create(
            context = context,
            redirects = redirects,
            io = dispatchers.io,
            main = dispatchers.main,
        )

        @Provides
        @Singleton
        fun provideCognitoAccounts(dispatchers: AppDispatchers): CognitoAccounts =
            ApiCognitoAccounts(
                transport = UrlConnectionHttpTransport(CognitoConfig.ENDPOINT),
                io = dispatchers.io,
            )

        @Provides
        @Singleton
        fun provideProjectShare(
            sessions: AccountSessionStore,
            accounts: CognitoAccounts,
            projects: ProjectRepository,
        ): ProjectShare = HttpProjectShare(
            sessions = sessions,
            accounts = accounts,
            projects = projects,
            api = UrlConnectionHttpTransport(ShareConfig.API_BASE),
            objects = UrlConnectionShareObjects(),
        )

        @Provides
        @Singleton
        fun provideSharedProjects(
            sessions: AccountSessionStore,
            accounts: CognitoAccounts,
        ): SharedProjects = HttpSharedProjects(
            sessions = sessions,
            accounts = accounts,
            api = UrlConnectionHttpTransport(ShareConfig.API_BASE),
        )

        @Provides
        @Singleton
        fun provideCardMedia(
            projects: SharedProjects,
            cache: CardCache,
        ): CardMedia = HttpCardMedia(
            projects = projects,
            cache = cache,
            objects = UrlConnectionShareObjects(),
        )
    }
}
