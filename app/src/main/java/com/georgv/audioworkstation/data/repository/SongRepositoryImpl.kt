package com.georgv.audioworkstation.data.repository

import com.georgv.audioworkstation.data.model.Song

import io.realm.kotlin.Realm
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID


class SongRepositoryImpl(private val realm: Realm) : SongRepository {

    override suspend fun createSong(name: String, wavPath: String?): Song {
        val songId = UUID.randomUUID().toString()
        return realm.write {
            copyToRealm(Song().apply {
                id = songId
                this.name = name
                this.wavFilePath = wavPath
            })
        }
    }

    override fun getAllSongs(): Flow<List<Song>> {
        return realm.query(Song::class)
            .asFlow()
            .map { it.list }
    }

    override fun getSongById(id: String): Song? {
        return realm.query(Song::class, "id == $0", id).first().find()
    }
}

