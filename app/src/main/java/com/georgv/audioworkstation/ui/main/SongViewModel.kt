package com.georgv.audioworkstation.ui.main


import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.*
import com.georgv.audioworkstation.audio.controller.AudioController
import com.georgv.audioworkstation.audio.effects.Compressor
import com.georgv.audioworkstation.audio.effects.Equalizer
import com.georgv.audioworkstation.audio.effects.Effect
import com.georgv.audioworkstation.audio.effects.Reverb
import com.georgv.audioworkstation.audio.processing.AudioProcessor
import com.georgv.audioworkstation.data.model.Song
import com.georgv.audioworkstation.data.repository.SongRepositoryImpl
import com.georgv.audioworkstation.data.model.Track
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

