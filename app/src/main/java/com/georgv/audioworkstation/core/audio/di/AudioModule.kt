package com.georgv.audioworkstation.core.audio.di

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.NativeAudioController
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindAudioController(
        controller: NativeAudioController
    ): AudioController
}
