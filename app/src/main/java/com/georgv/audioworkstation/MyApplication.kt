package com.georgv.audioworkstation

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context

class MyApplication : Application() {


    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_RUNNING,
            "Running",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Audio recording/playback status"
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_RUNNING = "running_channel"
    }
}

