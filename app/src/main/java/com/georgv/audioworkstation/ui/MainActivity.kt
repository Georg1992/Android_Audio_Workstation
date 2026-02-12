package com.georgv.audioworkstation.ui

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.georgv.audioworkstation.databinding.MainActivityBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: MainActivityBinding

    // Runtime permissions that actually need user approval
    private val runtimePermissions: Array<String>
        get() {
            val list = mutableListOf(Manifest.permission.RECORD_AUDIO)

            // Only if you plan to show notifications (e.g., for foreground service)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                list += Manifest.permission.POST_NOTIFICATIONS
                // Add READ_MEDIA_AUDIO only if you import audio from MediaStore
                // list += Manifest.permission.READ_MEDIA_AUDIO
            }

            return list.toTypedArray()
        }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val audioGranted = result[Manifest.permission.RECORD_AUDIO] == true
            if (!audioGranted) {
                // TODO: Show a simple UI message + disable recording buttons
                // (Don't crash. App should still open.)
            } else {
                // TODO: Enable recording features / init audio layer if needed
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        createNotificationChannelIfNeeded()

        requestPermissionsIfNeeded()

        // TODO: Setup main menu click listeners here
        // binding.btnFastRecord.setOnClickListener { ... }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = runtimePermissions.filter { permission ->
            ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED
        }

        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        } else {
            // All good
            // TODO: init audio layer / enable recording
        }
    }

    private fun createNotificationChannelIfNeeded() {

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