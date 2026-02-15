package com.georgv.audioworkstation.audio.streaming

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
import com.georgv.audioworkstation.R


class AudioStreamingService: Service()  {

    init {
        System.loadLibrary("native-lib")
        System.loadLibrary("audioworkstation")
    }


    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
    }


    override fun onBind(intent: Intent?): IBinder? {
        // This is a started service, not a bound service, so return null
        return null
    }



}
