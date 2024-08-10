package com.georgv.audioworkstation
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.databinding.MainActivityBinding


class MainActivity : AppCompatActivity() {

    private lateinit var binding: MainActivityBinding
    private val permissions = arrayOf<String>(
        Manifest.permission.FOREGROUND_SERVICE,
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.MODIFY_AUDIO_SETTINGS,
        Manifest.permission.READ_MEDIA_AUDIO,
        Manifest.permission.POST_NOTIFICATIONS
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this,permissions,1)
        checkAndRequestPermissions()
        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

    }

    private fun createNotificationChannel(){
        val channel = NotificationChannel(
            "running_channel",
            "running notifications",
            NotificationManager.IMPORTANCE_HIGH
        )
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun checkAndRequestPermissions() {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                // Permission not granted, request it
                ActivityCompat.requestPermissions(this, permissions, 1)
                return
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1) {
            // Check if all permissions are granted
            val allPermissionsGranted = grantResults.all { it == PackageManager.PERMISSION_GRANTED }

            if (allPermissionsGranted) {
                // All permissions are granted, proceed with your app logic
                // ...
            } else {
                // Some permissions are denied, handle accordingly
                // ...
            }
        }
    }
}