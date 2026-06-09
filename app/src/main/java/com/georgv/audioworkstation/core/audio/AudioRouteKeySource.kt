package com.georgv.audioworkstation.core.audio

import android.content.Context
import android.media.AudioManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

fun interface AudioRouteKeySource {
    fun routeKey(sampleRate: Int): String
}

@Singleton
class AndroidAudioRouteKeySource @Inject constructor(
    @ApplicationContext context: Context,
) : AudioRouteKeySource {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    override fun routeKey(sampleRate: Int): String =
        AudioRouteKeyProvider.routeKey(sampleRate, audioManager)
}
