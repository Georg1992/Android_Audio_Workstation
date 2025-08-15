package com.georgv.audioworkstation.audioprocessing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues.TAG
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.georgv.audioworkstation.MainActivity
import com.georgv.audioworkstation.R


class AudioStreamingService: Service()  {

    init {
        System.loadLibrary("native-lib")
        System.loadLibrary("audioworkstation")
    }

    private external fun startStreaming()
    private external fun destroyEngine()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service Started")
        ensureForeground()
        // Start your audio streaming here
        startStreaming()

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyEngine()
        Log.d(TAG, "Service Destroyed")
        // Stop your audio streaming here
    }

    override fun onBind(intent: Intent?): IBinder? {
        // This is a started service, not a bound service, so return null
        return null
    }

    private fun ensureForeground() {
        val channelId = "running_channel"
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Audio Streaming", NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification: Notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Audio Workstation")
            .setContentText("Streaming audio")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

}