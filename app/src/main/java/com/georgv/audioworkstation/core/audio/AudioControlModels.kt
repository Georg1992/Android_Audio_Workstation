package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

enum class ChannelMode {
    MONO,
    STEREO
}

data class RecordingSpec(
    val projectId: String,
    val trackId: String,
    val sampleRate: Int,
    val fileBitDepth: Int,
    val channelMode: ChannelMode
)

data class RecordingRequest(
    val sampleRate: Int,
    val fileBitDepth: Int,
    val channelMode: ChannelMode,
    val outputPath: String
)

data class PlaybackSpec(
    val sampleRate: Int,
    val wavFilePath: String,
    val gain: Float
)

fun ProjectEntity.toRecordingSpec(track: TrackEntity): RecordingSpec =
    RecordingSpec(
        projectId = id,
        trackId = track.id,
        sampleRate = sampleRate,
        fileBitDepth = fileBitDepth,
        channelMode = track.channelMode
    )

fun RecordingSpec.toRecordingRequest(outputPath: String): RecordingRequest =
    RecordingRequest(
        sampleRate = sampleRate,
        fileBitDepth = fileBitDepth,
        channelMode = channelMode,
        outputPath = outputPath
    )

fun ProjectEntity.toPlaybackSpec(track: TrackEntity): PlaybackSpec? =
    track.wavFilePath
        .takeIf { it.isNotBlank() }
        ?.let { wavFilePath ->
            PlaybackSpec(
                sampleRate = sampleRate,
                wavFilePath = wavFilePath,
                gain = track.gain / 100f
            )
        }
