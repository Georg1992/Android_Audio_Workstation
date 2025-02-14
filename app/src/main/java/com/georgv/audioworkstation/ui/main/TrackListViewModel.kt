package com.georgv.audioworkstation.ui.main


import android.app.Application
import android.util.Log
import androidx.lifecycle.*
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import io.realm.kotlin.Realm
import io.realm.kotlin.RealmConfiguration

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID


class TrackListViewModel(application: Application) : AndroidViewModel(application) {


    private var _isLoading = MutableLiveData<Boolean>()
    val isLoading: LiveData<Boolean> get() = _isLoading

    private var _taskStatus = MutableLiveData<Result<Unit>?>()
    val taskStatus: LiveData<Result<Unit>?> get() = _taskStatus

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()



    private val realm: Realm by lazy {
        Realm.open(RealmConfiguration.Builder(schema = setOf(Song::class)).build())
    }


    fun createNewSong(songName: String, wavDir: String?) {
        _isLoading.postValue(true) // Start loading
        viewModelScope.launch(Dispatchers.IO) {
            try {
                realm.write {
                    copyToRealm(Song().apply {
                        id = UUID.randomUUID().toString()
                        this.name = songName
                        this.wavFilePath = wavDir
                    })
                }
                _isLoading.postValue(false) // Stop loading
                _taskStatus.postValue(Result.success(Unit)) // Success message
            } catch (e: Exception) {
                _isLoading.postValue(false) // Stop loading
                _taskStatus.postValue(Result.failure(e)) // Error message
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

    fun clearTaskStatus() {
        _taskStatus.value = null // Reset task status
    }

    override fun onCleared() {
        super.onCleared()
        realm.close()
    }


}

