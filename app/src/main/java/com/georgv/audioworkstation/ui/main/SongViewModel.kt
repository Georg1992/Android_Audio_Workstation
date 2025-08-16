package com.georgv.audioworkstation.ui.main


import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.*
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.SongRepositoryImpl
import com.georgv.audioworkstation.data.Track
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration
import io.realm.kotlin.ext.query

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID


class SongViewModel(application: Application) : AndroidViewModel(application) {

    private var _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _currentSong = MutableLiveData<Song?>()
    val currentSong: LiveData<Song?> get() = _currentSong

    private val realm: Realm by lazy {
        Realm.open(RealmConfiguration.Builder(schema = setOf(Song::class)).build())
    }
    private val songRepo = SongRepositoryImpl(realm)


    fun createNewSong(songName: String, wavDir: String?) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val song = songRepo.createSong(songName, wavDir)
                _currentSong.postValue(song)
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
            realm.writeBlocking {
                copyToRealm(Track().apply {
                    this.songId = song.id
                    this.name = trackName
                    this.wavFilePath = wavFilePath
                    this.isRecording = true
                    this.timeStampStart = System.currentTimeMillis()
                    this.volume = 100f
                })
            }
        } catch (e: Exception) {
            Log.e("SongViewModel", "Failed to create track", e)
            null
        }
    }
    
    fun finishTrackRecording(trackId: String, duration: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm.write {
                    val track = query<Track>("id == $0", trackId).first().find()
                    track?.let {
                        it.isRecording = false
                        it.timeStampStop = System.currentTimeMillis()
                        it.duration = duration
                    }
                }
                loadTracksForCurrentSong() // Refresh tracks
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to finish track recording", e)
            }
        }
    }
    
    fun createNewSongWithTrack(songName: String, trackName: String = "Track 1"): Pair<Song?, Track?> {
        return try {
            Log.i("SongViewModel", "Creating song: $songName with track: $trackName")
            realm.writeBlocking {
                val song = copyToRealm(Song().apply {
                    id = UUID.randomUUID().toString()
                    name = songName
                    wavFilePath = null
                })
                
                val track = copyToRealm(Track().apply {
                    songId = song.id
                    name = trackName
                    wavFilePath = "" // Will be set when recording starts
                    isRecording = false
                    timeStampStart = System.currentTimeMillis()
                    volume = 100f
                })
                
                Log.i("SongViewModel", "Created song: ${song.name} (ID: ${song.id}) with track: ${track.name} (ID: ${track.id})")
                _currentSong.postValue(song)
                Pair(song, track)
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
                    _currentSong.postValue(song)
                } else {
                    // Handle case when song is not found
                }
            } catch (e: Exception) {
                // Handle any exception, for example logging or showing an error
                e.printStackTrace()
            }
        }
    }

    fun loadTracksForCurrentSong() {
        val song = _currentSong.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val tracks = realm.query<Track>("songId == $0", song.id).find()
                _tracks.value = tracks.toList()
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to load tracks for song", e)
            }
        }
    }

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
                loadTracksForCurrentSong() // Refresh tracks after update
            } catch (e: Exception) {
                Log.e("SongViewModel", "Failed to update track WAV path", e)
            }
        }
    }
    
    fun getRecordingTrack(): Track? {
        val song = _currentSong.value ?: return null
        return try {
            realm.query<Track>("songId == $0 AND isRecording == $1", song.id, true).first().find()
        } catch (e: Exception) {
            Log.e("SongViewModel", "Error finding recording track", e)
            null
        }
    }

    fun deleteTrackFromDb(id: String) {
        viewModelScope.launch() {


        }
    }


    private fun getSongById(id: String): Song? {
        return realm.query<Song>(Song::class, "id == $0", id).first().find()
    }



    fun updateTrackVolumeToDb(volume: Float, id: String) {

    }

    fun updateEffectToDb(effect:Effect, id: String) {

    }

    fun deleteEffectFromDb(tag: String,id: String){

    }


    override fun onCleared() {
        super.onCleared()
        realm.close()
    }


}

