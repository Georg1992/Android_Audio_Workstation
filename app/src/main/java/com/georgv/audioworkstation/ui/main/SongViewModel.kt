package com.georgv.audioworkstation.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.customHandlers.TypeConverter
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.SongDB
import com.georgv.audioworkstation.data.Track
import kotlinx.coroutines.*
import java.util.*


class SongViewModel(application: Application) : AndroidViewModel(application) {
    private var db: SongDB = SongDB.get(application, viewModelScope)

    private val _songList: LiveData<List<Song>> = db.songDao().getAllSongs()
    val songList: LiveData<List<Song>>
        get() = _songList

    private var _currentSong: Song? = null
    val currentSong: Song?
        get() = _currentSong

    private var songID: Long = 0
    private var _trackList: MutableLiveData<List<Track>> = MutableLiveData(listOf())
    val trackList: LiveData<List<Track>>
        get() = _trackList


    suspend fun createNewSong() {
        val newSong = Song(0, null, true, "Song ${songList.value?.size?.plus(1)}")
        val job = viewModelScope.async() {
            db.songDao().insert(newSong)
        }
        songID = job.await()
        _currentSong = getSongById(songID)
    }

    fun updateSongOnNavigate(song: Song){
        _currentSong = song
        songID = song.id
    }


    fun recordTrack(name: String, pcmDir: String, wavDir: String) {
        val newTrack = Track(
            0,
            true,
            name,
            pcmDir,
            wavDir,
            TypeConverter.dateToTimestamp(Date()),
            null,
            null,
            songID,
            ""
        )
        AudioController.lastRecorded = newTrack
        viewModelScope.launch {
            db.trackDao().insert(newTrack)
            _trackList.value = db.trackDao().getTracksBySongId(songID)
        }
    }


    fun stopRecordTrack() {
        val timestamp: Long = TypeConverter.dateToTimestamp(Date())
        viewModelScope.launch {
            val job = db.trackDao().getTrackInEdit()
            if (job != null) {
                val duration = timestamp - job.timeStampStart
                db.trackDao().trackUpdate(false, timestamp, duration, job.id)
                _trackList.value = db.trackDao().getTracksBySongId(songID)
            }
        }
    }



    fun deleteTrackFromDb(id: Long) {
        viewModelScope.launch() {
            db.trackDao().deleteById(id)
            _trackList.value = db.trackDao().getTracksBySongId(songID)
        }
    }


    private suspend fun getSongById(id: Long): Song {
        return db.songDao().getSongByID(id)
    }


    fun deleteSongFromDB(id: Long) {
        viewModelScope.launch {
            db.songDao().deleteById(id)
        }
    }

}