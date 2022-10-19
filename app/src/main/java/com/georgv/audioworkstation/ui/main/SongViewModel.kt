package com.georgv.audioworkstation.ui.main

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.customHandlers.AudioController
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

    private var songId: Long = 0

    init {
        runBlocking {
            val job = GlobalScope.async {
                val lastsong: Song? = db.songDao().getLastSong()
                when (lastsong != null) {
                    true -> songId = lastsong.id
                    false -> createNewSong()
                }
            }
            job.await()
        }

    }

    private val _trackList: LiveData<List<Track>> = db.trackDao().getTracksBySongId(songId)
    val trackList: LiveData<List<Track>>
        get() = _trackList


    fun createNewSong() {
        val newSong = Song(0, null, true, "Song")
        runBlocking {
            val job = viewModelScope.async(Dispatchers.IO) {
                insertSongToDb(newSong)
            }
            songId = job.await()
        }

    }

    fun recordTrack(name: String,pcmDir:String,wavDir:String) {
        val newTrack = Track(
            0,
            true,
            name,
            pcmDir,
            wavDir,
            TypeConverter.dateToTimestamp(Date()),
            null,
            null,
            songId
        )
        AudioController.lastRecorded = newTrack
        viewModelScope.launch(Dispatchers.IO) { insertTrackToDb(newTrack) }
    }


    fun stopRecordTrack() {
        val timestamp: Long = TypeConverter.dateToTimestamp(Date())
        viewModelScope.launch(Dispatchers.IO) {
            val job = db.trackDao().getTrackInEdit()
            if (job != null) {
                val duration = timestamp - job.timeStampStart
                updateTrackToDb(false, timestamp, duration, job.id)
            }

        }
    }

    private suspend fun insertSongToDb(thisSong: Song): Long {
        return db.songDao().insert(thisSong)
    }

    private suspend fun insertTrackToDb(track: Track): Long {
        return db.trackDao().insert(track)
    }

    private fun updateTrackToDb(
        isRecording: Boolean,
        timeStampStop: Long?,
        duration: Long?,
        id: Long
    ) {
        db.trackDao().trackUpdate(isRecording, timeStampStop, duration, id)
    }
}