package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** Session-only metadata for punch recording into an existing track row. */
data class RecordingPunchContext(
    val originalWavPath: String,
    val tempRecordingPath: String,
    val finalWavPath: String,
    val spliceStartInClipMs: Long,
    val sampleRateHz: Int,
    val fileBitDepth: Int,
)

data class PreparedExistingTrackRecording(
    val track: TrackEntity,
    val spliceStartInClipMs: Long,
    /** Global project timeline position (ms) used to seed native transport during punch record. */
    val recordingTransportStartMs: Long,
)

data class WavPunchSpliceResult(
    val durationMs: Long,
    val outputPath: String,
)
