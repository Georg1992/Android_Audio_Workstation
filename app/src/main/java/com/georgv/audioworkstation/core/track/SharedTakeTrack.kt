package com.georgv.audioworkstation.core.track

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** A shared take kept on the server: play it as short pieces, do not read a local WAV. */
fun TrackEntity.streamsSharedTake(): Boolean =
    wavFilePath.isBlank() && !contentHash.isNullOrBlank() && sourceDurationMs() > 0L
