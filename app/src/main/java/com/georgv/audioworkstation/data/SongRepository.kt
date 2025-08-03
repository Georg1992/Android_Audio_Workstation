package com.georgv.audioworkstation.data

import kotlinx.coroutines.flow.Flow

interface SongRepository {
    suspend fun createSong(name: String, wavPath: String?): Song
    fun getAllSongs(): Flow<List<Song>>
    fun getSongById(id: String): Song?
}