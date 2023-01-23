package com.georgv.audioworkstation.database

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.room.*
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: Song): Long

    @Query("SELECT * FROM songs ORDER BY id ASC")
    fun getAllSongs():LiveData<List<Song>>

    @Query("SELECT * FROM songs ORDER BY id DESC LIMIT 1")
    fun getLastSong():Song?

    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun getSongByID(id: Long):Song

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id:Long)


}
@Dao
interface TrackDao{
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track):Long

    @Query("SELECT * FROM tracks WHERE songID = :id")
    fun getLiveDataTracksBySongId(id:Long): LiveData<List<Track>>

    @Query("SELECT * FROM tracks WHERE songID = :id")
    suspend fun getTracksBySongId(id:Long): List<Track>

    @Query("DELETE FROM tracks WHERE songID = :id")
    suspend fun deleteTracksBySongId(id:Long)


    @Query("UPDATE tracks SET isRecording=:isRecording, timeStampStop=:timeStampStop, duration=:duration WHERE id=:id")
    suspend fun trackUpdate(isRecording: Boolean?, timeStampStop: Long?,duration:Long?, id: Long)

    @Query("UPDATE tracks SET volume=:volume WHERE id=:id")
    suspend fun trackVolumeUpdate(volume:Float, id: Long)

    @Query("SELECT * FROM tracks WHERE isRecording = 1")
    suspend fun getTrackInEdit():Track?

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun getTrackByID(id: Long):LiveData<Track>

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun deleteById(id:Long)


}