package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

enum class ChannelMode {
    MONO,
    STEREO
}

/**
 * UI-facing track gain range. Stored as a percent on [TrackEntity.gain] (0..100) so the casual UX
 * stays intuitive; the audio engine receives it as a normalized scalar (0..1) via [GainRange.toUnit].
 */
object GainRange {
    const val Min = 0f
    const val Max = 100f
    val Range: ClosedFloatingPointRange<Float> = Min..Max

    fun toUnit(percent: Float): Float = (percent / Max).coerceIn(0f, 1f)
}

/**
 * The sample rates a user can choose from when creating a project.
 *
 * Kept as an enum at the domain layer so the UI has a small, validated choice set; the raw
 * [hz] value is persisted on [ProjectEntity.sampleRate] so existing/legacy values continue to
 * round-trip untouched.
 */
enum class ProjectSampleRate(val hz: Int) {
    RATE_44_100(44_100),
    RATE_48_000(48_000);

    companion object {
        val Default = RATE_48_000
    }
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

data class TrackPlaybackLane(
    val trackId: String,
    val wavFilePath: String,
    val gain: Float
) {
    init {
        require(wavFilePath.isNotBlank()) { "Playback lane requires a WAV path." }
        require(gain in 0f..1f) { "Playback lane gain must be normalized to 0..1." }
    }
}

data class MultiPlaybackSpec(
    val sampleRate: Int,
    val lanes: List<TrackPlaybackLane>
) {
    init {
        require(ProjectSampleRate.values().any { it.hz == sampleRate }) {
            "Unsupported playback sample rate: $sampleRate."
        }
        require(lanes.size in 1..MaxLanes) { "Multi-playback requires 1..$MaxLanes lanes." }
    }

    companion object {
        const val MaxLanes = 8
    }
}

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
                gain = GainRange.toUnit(track.gain)
            )
        }

fun ProjectEntity.toMultiPlaybackSpec(tracks: List<TrackEntity>): MultiPlaybackSpec? {
    val lanes = tracks
        .mapNotNull { track ->
            track.wavFilePath
                .takeIf { it.isNotBlank() }
                ?.let { wavFilePath ->
                    TrackPlaybackLane(
                        trackId = track.id,
                        wavFilePath = wavFilePath,
                        gain = GainRange.toUnit(track.gain)
                    )
                }
        }

    if (lanes.size !in 1..MultiPlaybackSpec.MaxLanes) return null
    return MultiPlaybackSpec(sampleRate = sampleRate, lanes = lanes)
}
