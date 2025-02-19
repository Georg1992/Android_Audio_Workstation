package com.georgv.audioworkstation.ui.main


import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.*
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.data.Song
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


    fun createNewSong(songName: String, wavDir: String?) {
        _isLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val songId = UUID.randomUUID().toString()
                val addedSong = realm.write {
                    copyToRealm(Song().apply {
                        id = songId
                        this.name = songName
                        this.wavFilePath = wavDir
                    })
                }
                _currentSong.postValue(addedSong) // Updates LiveData
                _isLoading.postValue(false)
            } catch (e: Exception) {
                _isLoading.postValue(true)
                e.printStackTrace()
            }
        }
    }


    fun loadSong(songId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val song = realm.query<Song>("id == $0", songId).find().firstOrNull()
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



    fun updateSongOnNavigate(song: Song) {

    }

    fun createTrack() {

    }



    fun deleteTrackFromDb(id: String) {
        viewModelScope.launch() {


        }
    }


    private fun getSongById(id: String): Song? {
        return realm.query<Song>(Song::class, "id == $0", id).first().find()
    }


    private suspend fun deleteData(songID: String) {

    }

    fun updateTrackVolumeToDb(volume: Float, id: String) {

    }

    fun updateEffectToDb(effect:Effect, id: String) {

    }

    fun deleteEffectFromDb(tag: String,id: String){

    }


    fun deleteSongFromDB(id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            realm.write {
                val song = query<Song>(Song::class, "id == $0", id).first().find()
                song?.let { delete(it) }
            }
            Log.d("DELETING FROM THE DB", "ID: $id")
        }
    }


    override fun onCleared() {
        super.onCleared()
        realm.close()
    }


}

