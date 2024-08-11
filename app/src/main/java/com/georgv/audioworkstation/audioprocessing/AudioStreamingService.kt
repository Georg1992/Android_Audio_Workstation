package com.georgv.audioworkstation.audioprocessing

import android.app.Service
import android.content.ContentValues.TAG
import android.content.Intent
import android.os.IBinder
import android.util.Log


class AudioStreamingService: Service()  {

    init {
        System.loadLibrary("native-lib")
        System.loadLibrary("audioworkstation")
    }

    private external fun createEngine()
    private external fun destroyEngine()
    private external fun stringFromJNI()

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service Created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service Started")
        // Start your audio streaming here
        createEngine()

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
}