package com.georgv.audioworkstation.data.repository

import com.georgv.audioworkstation.data.model.Song

import kotlinx.coroutines.flow.Flow

interface SongRepository {
    suspend fun createSong(name: String, wavPath: String?): Song
    fun getAllSongs(): Flow<List<Song>>
    fun getSongById(id: String): Song?
}

