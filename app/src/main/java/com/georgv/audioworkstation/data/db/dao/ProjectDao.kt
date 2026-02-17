package com.georgv.audioworkstation.data.db.dao

import androidx.room.*
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity
import com.georgv.audioworkstation.data.db.relations.ProjectWithTracks
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {

    // Projects

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProject(project: ProjectEntity)

    @Query("SELECT * FROM projects ORDER BY createdAt DESC")
    fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("DELETE FROM projects WHERE id = :projectId")
    suspend fun deleteProject(projectId: String)


    // Tracks
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTracks(tracks: List<TrackEntity>)

    @Query("SELECT * FROM tracks WHERE projectId = :projectId")
    fun observeTracks(projectId: String): Flow<List<TrackEntity>>


    // Relations

    @Transaction
    @Query("SELECT * FROM projects WHERE id = :projectId")
    suspend fun getProjectWithTracks(projectId: String): ProjectWithTracks?
}


