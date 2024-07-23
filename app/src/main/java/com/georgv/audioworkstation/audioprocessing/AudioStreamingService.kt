package com.georgv.audioworkstation.audioprocessing

import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.georgv.audioworkstation.R


class AudioStreamingService: Service()  {
    override fun onBind(intent: Intent?): IBinder? {
        TODO("Not yet implemented")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }

    private fun start(){
        val notification = NotificationCompat.Builder(this,"running_channel" )
            .setSmallIcon(R.drawable.ic_plus_icon)
            .setContentTitle("Run is active")
            .setContentText("Some text")
            .build()

        startForeground(1,notification)

    }
}