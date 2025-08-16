package com.georgv.audioworkstation
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.databinding.MainActivityBinding
import com.georgv.audioworkstation.databinding.ActivityMainBinding
import com.georgv.audioworkstation.engine.NativeEngine
import java.io.File


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
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

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        val navController = navHostFragment.navController
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        val appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.FirstFragment, R.id.SecondFragment
            )
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        binding.bottomNavigation.setupWithNavController(navController)

        // Initialize native engine
        try {
            nativeEngine.init()
            Log.i("MainActivity", "Native engine initialized successfully")
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize native engine", e)
        }

        // Test native engine if permissions are available
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            testNativeEngine()
        }
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

    @RequiresApi(Build.VERSION_CODES.P)
    private fun checkAndRequestPermissions() {
        for (permission in permissions) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                // Permission not granted, request it
                ActivityCompat.requestPermissions(this, permissions, 1)
                return
            }
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
                
                Log.i("MainActivity", "Native engine test setup complete. Call startNativePlayback() to test audio.")
                
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
     * Start native audio playback (call this from a button or fragment)
     */
    fun startNativePlayback() {
        try {
            if (nativeEngine.start()) {
                Log.i("MainActivity", "Native playback started")
                Toast.makeText(this, "Native audio playback started", Toast.LENGTH_SHORT).show()
            } else {
                Log.e("MainActivity", "Failed to start native playback")
                Toast.makeText(this, "Failed to start native playback", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Error starting native playback", e)
        }
    }

    /**
     * Stop native audio playback
     */
    fun stopNativePlayback() {
        try {
            nativeEngine.stop()
            Log.i("MainActivity", "Native playback stopped")
            Toast.makeText(this, "Native audio playback stopped", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error stopping native playback", e)
        }
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