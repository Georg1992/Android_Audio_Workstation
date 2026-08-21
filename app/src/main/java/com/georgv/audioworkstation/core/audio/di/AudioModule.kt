package com.georgv.audioworkstation.core.audio.di

import com.georgv.audioworkstation.core.audio.AudioFilePathProvider
import com.georgv.audioworkstation.core.audio.AudioImporter
import com.georgv.audioworkstation.core.audio.CapturePort
import com.georgv.audioworkstation.core.audio.DefaultAudioFilePathProvider
import com.georgv.audioworkstation.core.audio.DefaultProjectFileStore
import com.georgv.audioworkstation.core.audio.DelegatingAudioImporter
import com.georgv.audioworkstation.core.audio.MeterPort
import com.georgv.audioworkstation.core.audio.MixdownPort
import com.georgv.audioworkstation.core.audio.NativeAudioController
import com.georgv.audioworkstation.core.audio.PlaybackPort
import com.georgv.audioworkstation.core.audio.ProjectFileStore
import com.georgv.audioworkstation.core.audio.RecordingStorageFsQuery
import com.georgv.audioworkstation.core.audio.StatFsRecordingStorageFsQuery
import com.georgv.audioworkstation.core.audio.WavAudioImporter
import com.georgv.audioworkstation.core.audio.waveform.WavWaveformPeakExtractor
import com.georgv.audioworkstation.core.audio.AndroidAudioRouteKeySource
import com.georgv.audioworkstation.core.audio.AudioRouteKeySource
import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfilePersistence
import com.georgv.audioworkstation.core.audio.capability.DeviceAudioCapabilityProfileStore
import com.georgv.audioworkstation.core.audio.capability.DeviceAudioIdentityProvider
import com.georgv.audioworkstation.core.audio.capability.DeviceAudioIdentitySource
import com.georgv.audioworkstation.core.audio.capability.LiveOverdubLatencySessionRecorder
import com.georgv.audioworkstation.core.audio.capability.RecordingSessionLatencyAudit
import com.georgv.audioworkstation.core.audio.capability.SessionTransportCapabilityGate
import com.georgv.audioworkstation.core.audio.capability.DefaultSessionTransportCapabilityGate
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AudioModule {

    @Binds
    @Singleton
    abstract fun bindPlaybackPort(
        controller: NativeAudioController,
    ): PlaybackPort

    @Binds
    @Singleton
    abstract fun bindCapturePort(
        controller: NativeAudioController,
    ): CapturePort

    @Binds
    @Singleton
    abstract fun bindMixdownPort(
        controller: NativeAudioController,
    ): MixdownPort

    @Binds
    @Singleton
    abstract fun bindMeterPort(
        controller: NativeAudioController,
    ): MeterPort

    @Binds
    @Singleton
    abstract fun bindAudioImporter(
        importer: DelegatingAudioImporter
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

    @Binds
    @Singleton
    abstract fun bindRecordingStorageFsQuery(
        query: StatFsRecordingStorageFsQuery
    ): RecordingStorageFsQuery

    @Binds
    @Singleton
    abstract fun bindAudioRouteKeySource(
        source: AndroidAudioRouteKeySource,
    ): AudioRouteKeySource

    @Binds
    @Singleton
    abstract fun bindDeviceAudioCapabilityProfilePersistence(
        store: DeviceAudioCapabilityProfileStore,
    ): DeviceAudioCapabilityProfilePersistence

    @Binds
    @Singleton
    abstract fun bindDeviceAudioIdentitySource(
        provider: DeviceAudioIdentityProvider,
    ): DeviceAudioIdentitySource

    @Binds
    @Singleton
    abstract fun bindLiveOverdubLatencySessionRecorder(
        audit: RecordingSessionLatencyAudit,
    ): LiveOverdubLatencySessionRecorder

    @Binds
    @Singleton
    abstract fun bindSessionTransportCapabilityGate(
        gate: DefaultSessionTransportCapabilityGate,
    ): SessionTransportCapabilityGate

    companion object {
        @Provides
        @Singleton
        fun provideWavWaveformPeakExtractor(): WavWaveformPeakExtractor = WavWaveformPeakExtractor()

        @Provides
        @Singleton
        fun provideWavAudioImporter(): WavAudioImporter = WavAudioImporter()
    }
}
