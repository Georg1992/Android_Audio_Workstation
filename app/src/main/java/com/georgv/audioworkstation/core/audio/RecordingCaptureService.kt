package com.georgv.audioworkstation.core.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.localization.withAppLanguage

/**
 * Microphone foreground service. While a take is open, leaving the app must not revoke capture.
 */
class RecordingCaptureService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startMicrophoneForeground()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startMicrophoneForeground() {
        val localized = withAppLanguage()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                localized.getString(R.string.recording_capture_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val notification = microphoneNotification(localized)
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
    }

    private fun microphoneNotification(localized: Context): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(localized.getString(R.string.recording_capture_notification_title))
            .setContentText(localized.getString(R.string.recording_capture_notification_text))
            .setSmallIcon(R.drawable.ic_recording_notification)
            .setOngoing(true)
            .build()

    private companion object {
        const val CHANNEL_ID = "recording_capture"
        const val NOTIFICATION_ID = 1001
    }
}
