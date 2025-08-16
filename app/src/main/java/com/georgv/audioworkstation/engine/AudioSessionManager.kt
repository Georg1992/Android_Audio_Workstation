package com.georgv.audioworkstation.engine

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Audio-optimized session manager for real-time audio operations.
 * Keeps audio data in memory to avoid database calls during audio processing.
 */
class AudioSessionManager {
    private val TAG = "AudioSessionManager"
    
    // Fast, thread-safe audio data (no Realm dependencies)
    data class TrackData(
        val id: String,
        val name: String,
        val wavFilePath: String,
        val volume: Float,
        val isRecording: Boolean = false
    )
    
    data class SessionData(
        val songId: String,
        val songName: String,
        val tracks: List<TrackData> = emptyList(),
        val selectedTrackIds: Set<String> = emptySet(),
        val isRecording: Boolean = false,
        val recordingTrackId: String? = null
    )
    
    // Current session state (thread-safe)
    private val _sessionData = MutableStateFlow<SessionData?>(null)
    val sessionData: StateFlow<SessionData?> = _sessionData.asStateFlow()
    
    // Audio thread can access these instantly (no DB calls)
    fun getTracksForPlayback(): List<TrackData> {
        val session = _sessionData.value ?: return emptyList()
        return session.tracks.filter { session.selectedTrackIds.contains(it.id) && !it.isRecording }
    }
    
    fun getRecordingTrack(): TrackData? {
        val session = _sessionData.value ?: return null
        return session.tracks.find { it.id == session.recordingTrackId }
    }
    
    fun isRecording(): Boolean {
        return _sessionData.value?.isRecording == true
    }
    
    fun isTrackSelected(trackId: String): Boolean {
        return _sessionData.value?.selectedTrackIds?.contains(trackId) == true
    }
    
    // Session management
    fun startSession(songId: String, songName: String) {
        Log.i(TAG, "Starting audio session for song: $songName")
        _sessionData.value = SessionData(songId = songId, songName = songName)
    }
    
    fun updateTracks(tracks: List<TrackData>) {
        val current = _sessionData.value ?: return
        _sessionData.value = current.copy(tracks = tracks)
        Log.d(TAG, "Updated session with ${tracks.size} tracks")
    }
    
    fun toggleTrackSelection(trackId: String) {
        val current = _sessionData.value ?: return
        val newSelection = if (current.selectedTrackIds.contains(trackId)) {
            current.selectedTrackIds - trackId
        } else {
            current.selectedTrackIds + trackId
        }
        _sessionData.value = current.copy(selectedTrackIds = newSelection)
        Log.d(TAG, "Track selection changed: $newSelection")
    }
    
    fun clearTrackSelection() {
        val current = _sessionData.value ?: return
        _sessionData.value = current.copy(selectedTrackIds = emptySet())
        Log.d(TAG, "Track selection cleared")
    }
    
    fun startRecording(trackId: String) {
        val current = _sessionData.value ?: return
        _sessionData.value = current.copy(
            isRecording = true,
            recordingTrackId = trackId
        )
        
        // Update the track's recording status
        val updatedTracks = current.tracks.map { track ->
            if (track.id == trackId) {
                track.copy(isRecording = true)
            } else track
        }
        _sessionData.value = current.copy(
            tracks = updatedTracks,
            isRecording = true,
            recordingTrackId = trackId
        )
        
        Log.i(TAG, "Started recording for track: $trackId")
    }
    
    fun stopRecording() {
        val current = _sessionData.value ?: return
        val recordingTrackId = current.recordingTrackId
        
        // Update the track's recording status
        val updatedTracks = current.tracks.map { track ->
            if (track.id == recordingTrackId) {
                track.copy(isRecording = false)
            } else track
        }
        
        _sessionData.value = current.copy(
            tracks = updatedTracks,
            isRecording = false,
            recordingTrackId = null
        )
        
        Log.i(TAG, "Stopped recording for track: $recordingTrackId")
    }
    
    fun addTrack(track: TrackData) {
        val current = _sessionData.value ?: return
        val updatedTracks = current.tracks + track
        _sessionData.value = current.copy(tracks = updatedTracks)
        Log.d(TAG, "Added track to session: ${track.name}")
    }
    
    companion object {
        @Volatile
        private var INSTANCE: AudioSessionManager? = null
        
        fun getInstance(): AudioSessionManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: AudioSessionManager().also { INSTANCE = it }
            }
        }
    }
}