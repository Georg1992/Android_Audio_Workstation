package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.data.repository.ProjectRepository
import com.georgv.audioworkstation.online.network.UrlConnectionHttpTransport
import com.georgv.audioworkstation.online.share.HttpSharedTakePublish
import com.georgv.audioworkstation.online.share.ShareConfig
import com.georgv.audioworkstation.online.share.ShareObjectTransport
import com.georgv.audioworkstation.online.share.SharedTakePublish
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SharedTakeModule {
    @Provides
    @Singleton
    fun provideSharedTakePublish(
        sessions: AccountSessionStore,
        accounts: CognitoAccounts,
        projects: ProjectRepository,
        objects: ShareObjectTransport,
    ): SharedTakePublish = HttpSharedTakePublish(
        sessions = sessions,
        accounts = accounts,
        projects = projects,
        api = UrlConnectionHttpTransport(ShareConfig.API_BASE),
        objects = objects,
    )
}
