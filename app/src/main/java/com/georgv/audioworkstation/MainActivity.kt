package com.georgv.audioworkstation
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.databinding.MainActivityBinding
import com.georgv.audioworkstation.engine.NativeEngine
import java.io.File


class MainActivity : AppCompatActivity() {

    private lateinit var binding: MainActivityBinding
    @RequiresApi(Build.VERSION_CODES.P)
    private val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        arrayOf<String>(
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
            Manifest.permission.READ_MEDIA_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS
        )
    } else {
        arrayOf<String>(
            Manifest.permission.FOREGROUND_SERVICE,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS,
        )
    }

    private val nativeEngine = NativeEngine()

    @RequiresApi(Build.VERSION_CODES.P)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this,permissions,1)
        checkAndRequestPermissions()

        binding = MainActivityBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Initialize native engine
        try {
            nativeEngine.init()
            Log.i("MainActivity", "Native engine initialized successfully")
            
            // Test native engine if permissions are available
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                testNativeEngine()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize native engine", e)
        }
    }

    /**
     * Simple test of the native audio engine
     */
    private fun testNativeEngine() {
        try {
            Log.i("MainActivity", "Testing native audio engine...")
            
            // Create test directory
            val testDir = File(filesDir, "test_audio")
            if (!testDir.exists()) {
                testDir.mkdirs()
            }
            
            // Create a test tone
            val testTonePath = nativeEngine.createTestTone(testDir, 440f, 1f)
            if (testTonePath != null) {
                Log.i("MainActivity", "Created test tone: $testTonePath")
                
                // Add track to native engine
                nativeEngine.clearTracks()
                nativeEngine.addTrack(testTonePath, 0.5f) // 50% volume
                nativeEngine.loadTracks()
                
                Log.i("MainActivity", "Native engine test setup complete.")
                
                // Optionally test offline mixing
                val mixPath = File(testDir, "test_mix.wav").absolutePath
                if (nativeEngine.offlineMixToWav(mixPath)) {
                    Log.i("MainActivity", "Offline mix created: $mixPath")
                }
            } else {
                Log.e("MainActivity", "Failed to create test tone")
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Native engine test failed", e)
        }
    }

    /**
     * Get the native engine instance for use in fragments
     */
    fun getNativeEngine(): NativeEngine = nativeEngine

    private fun createNotificationChannel(){
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O){
            val name ="running_channel"
            val description = "channel for audio running"
            val importance = NotificationManager.IMPORTANCE_DEFAULT
            val channel = NotificationChannel("running_channel",name,importance)
            channel.description = description
            val notificationManager: NotificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    fun checkAndRequestPermissions(){
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)

        if (permission != PackageManager.PERMISSION_GRANTED) {
            makeRequest()
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private fun makeRequest() {
        ActivityCompat.requestPermissions(this, permissions, 101)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            nativeEngine.release()
            Log.i("MainActivity", "Native engine released")
        } catch (e: Exception) {
            Log.e("MainActivity", "Error releasing native engine", e)
        }
    }
}