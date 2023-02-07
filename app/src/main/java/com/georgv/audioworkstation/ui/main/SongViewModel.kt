package com.georgv.audioworkstation.ui.main


import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.*
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.customHandlers.TypeConverter
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.SongDB
import com.georgv.audioworkstation.data.Track
import kotlinx.coroutines.*
import java.io.IOException
import java.lang.reflect.Type
import java.nio.file.Files
import java.nio.file.Paths
import java.util.*


class SongViewModel(application: Application) : AndroidViewModel(application) {

    private var db: SongDB = SongDB.get(application, viewModelScope)

    private val _songList: LiveData<List<Song>> = db.songDao().getAllSongs()
    val songList: LiveData<List<Song>>
        get() = _songList

    private var _currentSong: Song? = null
    val currentSong: Song?
        get() = _currentSong

    private var songID: MutableLiveData<Long> = MutableLiveData()
    private var _trackList: LiveData<List<Track>> = Transformations.switchMap(songID)
    { id ->
        db.trackDao().getLiveDataTracksBySongId(id)
    }
    val trackList: LiveData<List<Track>>
        get() = _trackList


    suspend fun createNewSong(songName: String, wavDir: String) {
        val newSong = Song(0, wavDir, true, songName)
        val job = viewModelScope.async() {
            db.songDao().insert(newSong)
        }
        val id = job.await()
        _currentSong = getSongById(id)
    }

    fun updateSongOnNavigate(song: Song) {
        _currentSong = song
        songID.value = song.id
    }

    fun createTrack(context: Context) {
        val songId = songID.value
        val idFromDb = lastFromDb()
        val newTrackId = newTrackId()
        val name = "track $newTrackId"
        val wavDir = "${context.filesDir.absolutePath}/${idFromDb}_song_$songId.wav"
        if (songId != null) {
            val newTrack = Track(
                0,
                true,
                name,
                100F,
                wavDir,
                TypeConverter.dateToTimestamp(Date()),
                null,
                null,
                songId,
                null,
                null,
                null
            )
            AudioController.trackToRecord = newTrack
            viewModelScope.launch {
                db.trackDao().insert(newTrack)
            }
        }
    }

    private fun lastFromDb():Int{
        val id = runBlocking {
            db.trackDao().getLastTrackId()
        } ?: return 1
        return id +1
    }

    private fun newTrackId():Int{
        val id = runBlocking {
            db.trackDao().getTracksAmountBySongId(songID.value)
        }
        return id+1
    }



    fun stopRecordTrack() {
        val timestamp: Long = TypeConverter.dateToTimestamp(Date())
        viewModelScope.launch {
            val job = db.trackDao().getTrackInEdit()
            if (job != null) {
                val duration = timestamp - job.timeStampStart
                db.trackDao().trackUpdate(false, timestamp, duration, job.id)
            }
        }
    }


    fun deleteTrackFromDb(id: Long) {
        viewModelScope.launch() {
            Log.d("DELETING FROM THE DB", "ID:$id")
            db.trackDao().deleteById(id)
        }
    }


    private suspend fun getSongById(id: Long): Song {
        return db.songDao().getSongByID(id)
    }

    private suspend fun deleteData(songID: Long) {
        val job = viewModelScope.async(Dispatchers.IO) {
            val tracks = db.trackDao().getTracksBySongId(songID)
            for (track in tracks) {
                try {
                    Files.delete(Paths.get(track.wavDir))
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        return job.await()
    }

    fun updateTrackVolumeToDb(volume: Float, id: Long) {
        viewModelScope.launch {
            db.trackDao().trackVolumeUpdate(volume, id)
        }
    }

    fun updateEffectToDb(effect:Effect, id: Long) {
        viewModelScope.launch {
            val effectString = TypeConverter.fromEffect(effect)
            when(effect){
                is Reverb -> db.trackDao().trackReverbUpdate(effectString,id)
                is Equalizer -> db.trackDao().trackEqUpdate(effectString,id)
                is Compressor -> db.trackDao().trackCompUpdate(effectString,id)
            }
        }
    }

    fun deleteEffectFromDb(tag: String,id: Long){
        viewModelScope.launch {
            when(tag){
                "rev" -> db.trackDao().trackReverbUpdate(null,id)
                "eq" ->db.trackDao().trackEqUpdate(null,id)
                "comp" -> db.trackDao().trackCompUpdate(null,id)
            }
        }
    }


    fun deleteSongFromDB(id: Long) {
        viewModelScope.launch {
            deleteData(id)
            db.trackDao().deleteTracksBySongId(id)
            db.songDao().deleteById(id)
        }
    }


}

