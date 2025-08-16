package com.georgv.audioworkstation.ui.main


import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.*
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.SongRepositoryImpl
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.data.RealmManager
import io.realm.kotlin.ext.query

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID
import com.georgv.audioworkstation.engine.AudioSessionManager


class SongViewModel(application: Application) : AndroidViewModel(application) {

    private var _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> get() = _currentSong

    private val realm = RealmManager.realm
    private val songRepo = SongRepositoryImpl(realm)
    
    // Audio-optimized session manager (fast, no DB calls for audio)
    private val audioSession = AudioSessionManager.getInstance()
    
    // Current song ID for reactive flows
    private val _currentSongId = MutableStateFlow<String?>(null)

    // Reactive tracks flow (UI updates automatically when DB changes)
    val tracks: StateFlow<List<Track>> = _currentSongId
        .filterNotNull()
        .flatMapLatest { songId ->
            realm.query<Track>("songId == $0", songId)
                .asFlow()
                .map { results -> 
                    val trackList = results.list.toList()
                    // Update audio session with latest data (for fast audio access)
                    val trackDataList = trackList.map { it.toTrackData() }
                    audioSession.updateTracks(trackDataList)
                    trackList
                }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
    
    // Track selection (delegated to AudioSessionManager for performance)
    val selectedTrackIds: StateFlow<Set<String>> = audioSession.sessionData
        .map { it?.selectedTrackIds ?: emptySet() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptySet()
        )
    
    // Helper to convert Realm Track to AudioSessionManager.TrackData
    private fun Track.toTrackData() = AudioSessionManager.TrackData(
        id = id,
        name = name ?: "Unnamed Track",
        wavFilePath = wavFilePath,
        volume = volume,
        isRecording = isRecording
    )


    fun createNewSong(songName: String, wavDir: String?) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val song = songRepo.createSong(songName, wavDir)
                // Create a thread-safe copy for UI
                val songCopy = Song().apply {
                    id = song.id
                    name = song.name
                    wavFilePath = song.wavFilePath
                }
                _currentSong.postValue(songCopy)
                
                // Start audio session for fast audio access
                _currentSongId.value = song.id
                audioSession.startSession(song.id, song.name)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.postValue(false)
            }
        }
    }
    
    fun createTrackForCurrentSong(trackName: String, wavFilePath: String): Track? {
        val song = _currentSong.value ?: return null
        return try {
            val track = realm.writeBlocking {
                copyToRealm(Track().apply {
                    this.songId = song.id
                    this.name = trackName
                    this.wavFilePath = wavFilePath
                    this.isRecording = true  // Start recording immediately for Fast Record
                    this.timeStampStart = System.currentTimeMillis()
                    this.volume = 100f
                })
            }
            
            // Create thread-safe copy for UI
            val trackCopy = track?.let { 
                Track().apply {
                    id = it.id
                    songId = it.songId
                    name = it.name
                    wavFilePath = it.wavFilePath
                    isRecording = it.isRecording
                    timeStampStart = it.timeStampStart
                    timeStampStop = it.timeStampStop
                    duration = it.duration
                    volume = it.volume
                }
            }
            
            Log.i("SongViewModel", "Created track: ${trackCopy?.name} - Recording: ${trackCopy?.isRecording}")
            // Refresh tracks to update UI
            loadTracksForCurrentSong()
            trackCopy
        } catch (e: Exception) {
            Log.e("SongViewModel", "Failed to create track", e)
            null
        }
    }
    
    fun startTrackRecording(trackId: String) {
        Log.i("SongViewModel", "startTrackRecording called for track: $trackId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm.write {
                    val track = query<Track>("id == $0", trackId).first().find()
                    track?.let {
                        Log.i("SongViewModel", "Setting isRecording = true for track: ${it.name}")
                        it.isRecording = true
                        it.timeStampStart = System.currentTimeMillis()
                    } ?: Log.e("SongViewModel", "Track not found for ID: $trackId")
                }
                Log.i("SongViewModel", "Calling loadTracksForCurrentSong to refresh UI")
                loadTracksForCurrentSong() // Refresh tracks
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to start track recording", e)
            }
        }
    }

    fun finishTrackRecording(trackId: String, duration: Long) {
        Log.i("SongViewModel", "finishTrackRecording called for track: $trackId")
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm.write {
                    val track = query<Track>("id == $0", trackId).first().find()
                    track?.let {
                        Log.i("SongViewModel", "Setting isRecording = false for track: ${it.name}")
                        it.isRecording = false
                        it.timeStampStop = System.currentTimeMillis()
                        it.duration = duration
                    } ?: Log.e("SongViewModel", "Track not found for ID: $trackId")
                }
                Log.i("SongViewModel", "Calling loadTracksForCurrentSong to refresh UI")
                loadTracksForCurrentSong() // Refresh tracks
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to finish track recording", e)
            }
        }
    }
    
    fun createNewSongWithTrack(songName: String, trackName: String = "Track 1"): Pair<Song?, Track?> {
        return try {
            Log.i("SongViewModel", "Creating song: $songName with track: $trackName")
            
            // First create the song only
            val song = realm.writeBlocking {
                copyToRealm(Song().apply {
                    id = UUID.randomUUID().toString()
                    name = songName
                    wavFilePath = null
                })
            }
            
            if (song != null) {
                Log.i("SongViewModel", "Song created successfully: ${song.name} (ID: ${song.id})")
                // Create a thread-safe copy for UI
                val songCopy = Song().apply {
                    id = song.id
                    name = song.name
                    wavDir = song.wavDir
                    timeStampStart = song.timeStampStart
                    timeStampStop = song.timeStampStop
                }
                _currentSong.postValue(songCopy)
                
                // Start audio session for fast audio access
                _currentSongId.value = song.id
                audioSession.startSession(song.id, song.name)
                
                // Then try to create the track separately
                val track = try {
                    realm.writeBlocking {
                        copyToRealm(Track().apply {
                            songId = song.id
                            name = trackName
                            wavFilePath = "" // Will be set when recording starts
                            isRecording = false
                            timeStampStart = System.currentTimeMillis()
                            volume = 100f
                        })
                    }
                } catch (trackError: Exception) {
                    Log.e("SongViewModel", "Failed to create track (schema issue), creating without track", trackError)
                    null
                }
                
                if (track != null) {
                    Log.i("SongViewModel", "Track created successfully: ${track.name} (ID: ${track.id})")
                } else {
                    Log.w("SongViewModel", "Song created but track creation failed - proceeding with song only")
                }
                
                Pair(song, track)
            } else {
                Log.e("SongViewModel", "Failed to create song")
                Pair(null, null)
            }
        } catch (e: Exception) {
            Log.e("SongViewModel", "Failed to create song with track", e)
            Pair(null, null)
        }
    }

    fun loadSongByID(songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val song = songRepo.getSongById(songId)
                if (song != null) {
                    // Create a thread-safe copy for UI
                    val songCopy = Song().apply {
                        id = song.id
                        name = song.name
                        wavFilePath = song.wavFilePath
                    }
                    _currentSong.postValue(songCopy)
                    
                    // Start audio session for fast audio access
                    _currentSongId.value = song.id
                    audioSession.startSession(song.id, song.name)
                } else {
                    // Handle case when song is not found
                    Log.w("SongViewModel", "Song with ID $songId not found")
                }
            } catch (e: Exception) {
                // Handle any exception, for example logging or showing an error
                Log.e("SongViewModel", "Error loading song by ID: $songId", e)
                e.printStackTrace()
            }
        }
    }

    // loadTracksForCurrentSong() is now replaced by reactive flows
    // Tracks automatically update when database changes via tracks StateFlow

    fun deleteSongFromDB(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            realm.write {
                val song = songRepo.getSongById(id)
                song?.let { delete(it) }
            }
            Log.d("DELETING FROM THE DB", "ID: $id")
        }
    }



    fun updateSongOnNavigate(song: Song) {

    }


    fun updateTrackWavPath(trackId: String, wavPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm.write {
                    val track = query<Track>("id == $0", trackId).first().find()
                    track?.let {
                        it.wavFilePath = wavPath
                        it.isRecording = true
                    }
                }
                // Tracks automatically refresh via reactive flows
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to update track WAV path", e)
            }
        }
    }
    
    fun getRecordingTrack(): Track? {
        val song = _currentSong.value ?: return null
        return try {
            val track = realm.query<Track>("songId == $0 AND isRecording == $1", song.id, true).first().find()
            // Return thread-safe copy
            track?.let {
                Track().apply {
                    id = it.id
                    songId = it.songId
                    name = it.name
                    wavFilePath = it.wavFilePath
                    isRecording = it.isRecording
                    timeStampStart = it.timeStampStart
                    timeStampStop = it.timeStampStop
                    duration = it.duration
                    volume = it.volume
                }
            }
        } catch (e: Exception) {
            Log.e("SongViewModel", "Error finding recording track", e)
            null
        }
    }
    
    // Track selection methods (delegated to AudioSessionManager for performance)
    fun toggleTrackSelection(trackId: String) {
        audioSession.toggleTrackSelection(trackId)
    }
    
    fun clearTrackSelection() {
        audioSession.clearTrackSelection()
    }
    
    fun getSelectedTracks(): List<Track> {
        val selectedIds = selectedTrackIds.value
        return tracks.value.filter { selectedIds.contains(it.id) }
    }
    
    fun isTrackSelected(trackId: String): Boolean {
        return audioSession.isTrackSelected(trackId)
    }

    fun deleteTrackFromDb(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm.write {
                    val track = query<Track>("id == $0", trackId).first().find()
                    track?.let { delete(it) }
                }
                Log.i("SongViewModel", "Track deleted: $trackId")
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to delete track", e)
            }
        }
    }

    fun updateTrackVolumeToDb(volume: Float, trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm.write {
                    val track = query<Track>("id == $0", trackId).first().find()
                    track?.let { 
                        it.volume = volume
                        Log.i("SongViewModel", "Updated track volume: ${it.name} -> $volume")
                    }
                }
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to update track volume", e)
            }
        }
    }

    private fun getSongById(id: String): Song? {
        return realm.query<Song>(Song::class, "id == $0", id).first().find()
    }

    // TODO: Effect management methods will be implemented when Effect class is created
    // fun updateEffectToDb(effect:Effect, id: String) { }
    // fun deleteEffectFromDb(tag: String,id: String){ }


    override fun onCleared() {
        super.onCleared()
        // Don't close realm here since it's shared via RealmManager
    }


}

