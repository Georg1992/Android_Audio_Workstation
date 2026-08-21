package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.TrackEntity

internal object NoopProjectFileStore : ProjectFileStore {
    override suspend fun deleteTrackFile(track: TrackEntity) = Unit
    override suspend fun deleteProjectFolder(projectId: String) = Unit
}

