package com.georgv.audioworkstation.core.audio.di

import com.georgv.audioworkstation.core.audio.AudioController
import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImporter
import com.georgv.audioworkstation.core.audio.DefaultAudioFilePathProvider
import com.georgv.audioworkstation.core.audio.DefaultProjectFileStore
import com.georgv.audioworkstation.core.audio.NativeAudioController
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.audio.WavAudioImporter
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

    @Binds
    @Singleton
    abstract fun bindAudioImporter(
        importer: WavAudioImporter
    ): AudioImporter

    @Binds
    @Singleton
    abstract fun bindAudioFilePathProvider(
        provider: DefaultAudioFilePathProvider
    ): AudioFilePathProvider

    @Binds
    @Singleton
    abstract fun bindProjectFileStore(
        store: DefaultProjectFileStore
    ): ProjectFileStore
}
