package com.georgv.audioworkstation.database

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track

@Dao
interface SongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: Song): Long

    @Query("SELECT * FROM songs ORDER BY id ASC")
    fun getAllSongs():LiveData<List<Song>>

    @Query("SELECT * FROM songs WHERE inEditMode = :bool")
    fun getSongInEdit(bool: Boolean):LiveData<Song>

    @Query("SELECT * FROM songs ORDER BY id DESC LIMIT 1")
    fun getLastSong():Song?

}
@Dao
interface TrackDao{
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(track: Track):Long

    @Query("SELECT * FROM tracks WHERE songID = :id")
    fun getTracksBySongId(id:Long?):LiveData<List<Track>>

    @Query("UPDATE tracks SET isRecording=:isRecording, timeStampStop=:timeStampStop, duration=:duration WHERE id=:id")
    fun trackUpdate(isRecording: Boolean?, timeStampStop: Long?,duration:Long?, id: Long)

    @Query("SELECT * FROM tracks WHERE isRecording = 1")
    fun getTrackInEdit():Track?

}