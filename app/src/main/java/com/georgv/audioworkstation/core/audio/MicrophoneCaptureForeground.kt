package com.georgv.audioworkstation.core.audio

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Keeps microphone capture in a foreground service for the life of a take. */
interface MicrophoneCaptureForeground {
    fun start()

    fun stop()
}

/** Used when a caller has no process-wide service to stop. */
object InactiveMicrophoneCaptureForeground : MicrophoneCaptureForeground {
    override fun start() = Unit

    override fun stop() = Unit
}

@Singleton
class AndroidMicrophoneCaptureForeground @Inject constructor(
    @ApplicationContext private val context: Context,
) : MicrophoneCaptureForeground {
    override fun start() {
        val intent = Intent(context, RecordingCaptureService::class.java)
        ContextCompat.startForegroundService(context, intent)
    }

    override fun stop() {
        context.stopService(Intent(context, RecordingCaptureService::class.java))
    }
}
