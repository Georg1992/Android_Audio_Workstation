package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.data.db.entities.ProjectEntity
import com.georgv.audioworkstation.data.db.entities.TrackEntity

enum class ChannelMode {
    MONO,
    STEREO;

    fun channelCount(): Int =
        when (this) {
            MONO -> 1
            STEREO -> 2
        }
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
    val channelMode: ChannelMode,
    /** Playhead/timeline offset (ms) used to seed native [transportFrame] on recording-only start (Clock.3). */
    val timelineStartOffsetMs: Long = 0L,
) {
    init {
        require(timelineStartOffsetMs >= 0L) { "Recording timeline start must be non-negative." }
    }
}

data class RecordingRequest(
    val sampleRate: Int,
    val fileBitDepth: Int,
    val channelMode: ChannelMode,
    val outputPath: String,
    val timelineStartOffsetMs: Long = 0L,
)

data class PlaybackSpec(
    val sampleRate: Int,
    val wavFilePath: String,
    val gain: Float,
    val startPositionMs: Long = 0L,
    /** Absolute timeline end (ms); 0 = native lane-drain completion (tests only). */
    val sessionTimelineEndMs: Long = 0L,
) {
    init {
        require(startPositionMs >= 0L) { "Playback start position must be non-negative." }
        require(sessionTimelineEndMs >= 0L) { "Session timeline end must be non-negative." }
    }
}

data class TrackPlaybackLane(
    val trackId: String,
    val wavFilePath: String,
    val gain: Float,
    /** Timeline position (ms) where this clip starts on the project timeline. */
    val timelineClipStartMs: Long = 0L,
    /** Clip length on the timeline (ms); 0 = no explicit timeline end (native uses WAV drain only). */
    val timelineClipDurationMs: Long = 0L,
) {
    init {
        require(wavFilePath.isNotBlank()) { "Playback lane requires a WAV path." }
        require(gain in 0f..1f) { "Playback lane gain must be normalized to 0..1." }
        require(timelineClipStartMs >= 0L) { "Timeline clip start must be non-negative." }
        require(timelineClipDurationMs >= 0L) { "Timeline clip duration must be non-negative." }
    }
}

/** WAV read offset (ms) for a lane at transport position [playheadMs]. */
fun laneSourceOffsetMs(playheadMs: Long, clipStartMs: Long): Long =
    (playheadMs - clipStartMs).coerceAtLeast(0L)

fun isLaneAudibleAtPlayhead(playheadMs: Long, clipStartMs: Long, clipDurationMs: Long): Boolean {
    if (playheadMs < clipStartMs) return false
    if (clipDurationMs <= 0L) return true
    return playheadMs < clipStartMs + clipDurationMs
}

data class MultiPlaybackSpec(
    val sampleRate: Int,
    val lanes: List<TrackPlaybackLane>,
    val startPositionMs: Long = 0L,
    /** Absolute timeline end (ms); 0 = native lane-drain completion (tests only). */
    val sessionTimelineEndMs: Long = 0L,
) {
    init {
        require(ProjectSampleRate.values().any { it.hz == sampleRate }) {
            "Unsupported playback sample rate: $sampleRate."
        }
        require(lanes.size in 1..MaxLanes) { "Multi-playback requires 1..$MaxLanes lanes." }
        require(startPositionMs >= 0L) { "Playback start position must be non-negative." }
        require(sessionTimelineEndMs >= 0L) { "Session timeline end must be non-negative." }
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
        channelMode = track.channelMode,
        timelineStartOffsetMs = track.timelineStartOffsetMs.coerceAtLeast(0L),
    )

fun RecordingSpec.toRecordingRequest(outputPath: String): RecordingRequest =
    RecordingRequest(
        sampleRate = sampleRate,
        fileBitDepth = fileBitDepth,
        channelMode = channelMode,
        outputPath = outputPath,
        timelineStartOffsetMs = timelineStartOffsetMs,
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
                        gain = GainRange.toUnit(track.gain),
                        timelineClipStartMs = track.timelineStartOffsetMs.coerceAtLeast(0L),
                        timelineClipDurationMs = track.duration ?: 0L,
                    )
                }
        }

    if (lanes.size !in 1..MultiPlaybackSpec.MaxLanes) return null
    return MultiPlaybackSpec(sampleRate = sampleRate, lanes = lanes)
}
