package com.georgv.audioworkstation.engine

import android.util.Log
import androidx.fragment.app.Fragment
import com.georgv.audioworkstation.MainActivity

/**
 * Utility class to help fragments interact with the native audio engine
 */
class NativeAudioManager private constructor(private val nativeEngine: NativeEngine) {
    
    companion object {
        private const val TAG = "NativeAudioManager"
        
        /**
         * Get the native audio manager from a fragment
         */
        fun from(fragment: Fragment): NativeAudioManager? {
            val activity = fragment.activity as? MainActivity
            return activity?.let { NativeAudioManager(it.getNativeEngine()) }
        }
    }
    
    /**
     * Start native audio playback
     */
    fun startPlayback(): Boolean {
        return try {
            if (nativeEngine.start()) {
                Log.i(TAG, "Native playback started")
                true
            } else {
                Log.e(TAG, "Failed to start native playback")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting native playback", e)
            false
        }
    }
    
    /**
     * Stop native audio playback
     */
    fun stopPlayback() {
        try {
            nativeEngine.stop()
            Log.i(TAG, "Native playback stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping native playback", e)
        }
    }
    
    /**
     * Reset playback positions to beginning
     */
    fun resetPlayback() {
        try {
            nativeEngine.reset()
            Log.i(TAG, "Native playback reset")
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting native playback", e)
        }
    }
    
    /**
     * Clear all tracks
     */
    fun clearTracks() {
        try {
            nativeEngine.clearTracks()
            Log.i(TAG, "All tracks cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing tracks", e)
        }
    }
    
    /**
     * Add a track with default volume
     */
    fun addTrack(path: String): Boolean {
        return try {
            nativeEngine.addTrack(path)
            Log.i(TAG, "Track added: $path")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding track: $path", e)
            false
        }
    }
    
    /**
     * Add a track with specific volume
     */
    fun addTrack(path: String, volume: Float): Boolean {
        return try {
            nativeEngine.addTrack(path, volume)
            Log.i(TAG, "Track added: $path (volume: $volume)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error adding track: $path", e)
            false
        }
    }
    
    /**
     * Load all tracks into memory
     */
    fun loadTracks(): Boolean {
        return try {
            nativeEngine.loadTracks()
            Log.i(TAG, "All tracks loaded")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error loading tracks", e)
            false
        }
    }
    
    /**
     * Mix all tracks to a WAV file
     */
    fun mixToWav(outputPath: String): Boolean {
        return try {
            val success = nativeEngine.offlineMixToWav(outputPath)
            if (success) {
                Log.i(TAG, "Mix created: $outputPath")
            } else {
                Log.e(TAG, "Failed to create mix: $outputPath")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error creating mix: $outputPath", e)
            false
        }
    }
    
    /**
     * Get direct access to the native engine for advanced use
     */
    fun getNativeEngine(): NativeEngine = nativeEngine
    
    /**
     * Start recording audio to the specified file path
     */
    fun startRecording(outputPath: String): Boolean {
        return try {
            if (nativeEngine.startRecording(outputPath)) {
                Log.i(TAG, "Recording started: $outputPath")
                true
            } else {
                Log.e(TAG, "Failed to start recording: $outputPath")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting recording: $outputPath", e)
            false
        }
    }
    
    /**
     * Stop current recording
     */
    fun stopRecording() {
        try {
            nativeEngine.stopRecording()
            Log.i(TAG, "Recording stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping recording", e)
        }
    }
    
    /**
     * Check if currently recording
     */
    fun isRecording(): Boolean {
        return try {
            nativeEngine.isRecording()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking recording state", e)
            false
        }
    }
}