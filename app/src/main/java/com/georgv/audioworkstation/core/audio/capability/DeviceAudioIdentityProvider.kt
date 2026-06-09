package com.georgv.audioworkstation.core.audio.capability

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.georgv.audioworkstation.core.audio.latency.AudioRouteDeviceInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceAudioIdentityProvider @Inject constructor(
    @ApplicationContext private val context: Context,
) : DeviceAudioIdentitySource {
    override fun currentIdentity(sampleRate: Int): DeviceAudioIdentity {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val route = AudioRouteDeviceInfo.current(sampleRate, audioManager)
        return DeviceAudioIdentity(
            deviceManufacturer = Build.MANUFACTURER.orEmpty(),
            deviceModel = Build.MODEL.orEmpty(),
            androidVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            routeKey = route.routeKey,
            routeType = AudioRouteType.fromRouteTypeLabel(route.routeType),
            sampleRate = sampleRate,
        )
    }
}
