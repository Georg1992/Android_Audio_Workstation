package com.georgv.audioworkstation.online

import com.georgv.audioworkstation.online.share.ShareObjectTransport
import com.georgv.audioworkstation.online.share.UrlConnectionShareObjects
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ShareObjectModule {
    @Provides
    @Singleton
    fun provideShareObjects(): ShareObjectTransport = UrlConnectionShareObjects()
}
