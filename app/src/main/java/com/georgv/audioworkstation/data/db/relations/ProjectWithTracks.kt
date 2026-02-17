package com.georgv.audioworkstation.data.db.relations

import androidx.room.Embedded
import androidx.room.Relation
import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

data class ProjectWithTracks(
    @Embedded val project: ProjectEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "projectId"
    )
    val tracks: List<TrackEntity>
)
