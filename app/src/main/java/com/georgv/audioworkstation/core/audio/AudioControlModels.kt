package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.track.effectiveLoopEndMs
import com.georgv.audioworkstation.core.track.effectiveLoopStartMs
import com.georgv.audioworkstation.core.track.sourceDurationMs
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

/** UI-facing stereo pan: -1 = full left, 0 = center, +1 = full right. Stored on [TrackEntity.pan]. */
object PanRange {
    const val Min = -1f
    const val Max = 1f
    const val Center = 0f
    val Range: ClosedFloatingPointRange<Float> = Min..Max

    fun clamp(value: Float): Float = value.coerceIn(Min, Max)

    fun label(pan: Float): String =
        when {
            pan == 0f -> "C"
            pan < 0f -> "L"
            else -> "R"
        }

    fun formatValue(pan: Float): String {
        val clamped = clamp(pan)
        return if (clamped == 0f) "0.0" else String.format("%+.1f", clamped)
    }
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
        val Default = RATE_44_100
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

data class TrackPlaybackLane(
    val trackId: String,
    val wavFilePath: String,
    val gain: Float,
    /** Normalized stereo pan -1..+1; 0 = center. */
    val pan: Float = 0f,
    /** Timeline position (ms) where this clip starts on the project timeline. */
    val timelineClipStartMs: Long = 0L,
    /** Clip length on the timeline (ms); 0 = no explicit timeline end (native uses WAV drain only). */
    val timelineClipDurationMs: Long = 0L,
    val loopEnabled: Boolean = false,
    /** Track-local source loop region start (ms from WAV start). */
    val loopSourceStartMs: Long = 0L,
    /** Track-local source loop region end (ms from WAV start). */
    val loopSourceEndMs: Long = 0L,
) {
    init {
        require(wavFilePath.isNotBlank()) { "Playback lane requires a WAV path." }
        require(gain in 0f..1f) { "Playback lane gain must be normalized to 0..1." }
        require(pan in -1f..1f) { "Playback lane pan must be normalized to -1..1." }
        require(timelineClipStartMs >= 0L) { "Timeline clip start must be non-negative." }
        require(timelineClipDurationMs >= 0L) { "Timeline clip duration must be non-negative." }
        require(loopSourceStartMs >= 0L) { "Loop source start must be non-negative." }
        require(loopSourceEndMs >= 0L) { "Loop source end must be non-negative." }
    }
}

/** WAV read offset (ms) for a lane at transport position [playheadMs] (non-loop lanes). */
fun laneSourceOffsetMs(playheadMs: Long, clipStartMs: Long): Long =
    laneSourceReadOffsetMs(
        playheadMs = playheadMs,
        clipStartMs = clipStartMs,
        loopEnabled = false,
        loopSourceStartMs = 0L,
        loopSourceEndMs = 0L,
    )

/** Source read position (ms) including loop wrap; matches native lane seek mapping. */
fun laneSourceReadOffsetMs(
    playheadMs: Long,
    clipStartMs: Long,
    loopEnabled: Boolean,
    loopSourceStartMs: Long,
    loopSourceEndMs: Long,
): Long {
    val clipOffset = (playheadMs - clipStartMs).coerceAtLeast(0L)
    if (!loopEnabled) return clipOffset
    val loopLength = (loopSourceEndMs - loopSourceStartMs).coerceAtLeast(1L)
    return loopSourceStartMs + (clipOffset % loopLength)
}

fun isLaneAudibleAtPlayhead(
    playheadMs: Long,
    clipStartMs: Long,
    clipDurationMs: Long,
    loopEnabled: Boolean = false,
): Boolean {
    if (playheadMs < clipStartMs) return false
    if (loopEnabled) return true
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

fun ProjectEntity.toMultiPlaybackSpec(tracks: List<TrackEntity>): MultiPlaybackSpec? {
    val lanes = tracks
        .mapNotNull { track ->
            track.wavFilePath
                .takeIf { it.isNotBlank() }
                ?.let { wavFilePath ->
                    val effectiveStartMs = track.effectiveLoopStartMs()
                    val effectiveEndMs = track.effectiveLoopEndMs()
                    TrackPlaybackLane(
                        trackId = track.id,
                        wavFilePath = wavFilePath,
                        gain = GainRange.toUnit(track.gain),
                        pan = PanRange.clamp(track.pan),
                        timelineClipStartMs = track.timelineStartOffsetMs.coerceAtLeast(0L),
                        timelineClipDurationMs = track.sourceDurationMs(),
                        loopEnabled = track.isLoop,
                        loopSourceStartMs = effectiveStartMs,
                        loopSourceEndMs = effectiveEndMs,
                    )
                }
        }

    if (lanes.size !in 1..MultiPlaybackSpec.MaxLanes) return null
    return MultiPlaybackSpec(sampleRate = sampleRate, lanes = lanes)
}

/** UI state for the master session peak / safety indicator on the global playhead panel. */
enum class MasterPeakIndicatorLevel {
    /** No active playback session peak to display. */
    Inactive,
    /** Held pre-soft-clip peak is below the master safety knee. */
    Green,
    /**
     * Held pre-soft-clip peak reached the master safety soft-clip threshold at least once
     * during this peak-hold window (~-0.09 dBFS at 0.99 linear). Yellow means the safety
     * stage has engaged — not hard clipping.
     */
    Yellow,
    /** Held pre-soft-clip peak reached severe overload (safety stage saturated). */
    Red,
}

data class MasterOutputMeterState(
    val peakDbText: String = "0 dB",
    val indicatorLevel: MasterPeakIndicatorLevel = MasterPeakIndicatorLevel.Inactive,
)

/** Converts native master peak hold (linear, pre soft-clip) into playhead-panel display values. */
object MasterPeakMeter {
    /**
     * Matches native [kMasterSafetyThreshold] in AudioEngine.cpp (~-0.09 dBFS).
     * Green: held peak < this. Yellow: held peak >= this and < [SEVERE_OVERLOAD_THRESHOLD_LINEAR].
     */
    const val SOFT_CLIP_THRESHOLD_LINEAR = 0.99f

    /**
     * Pre-soft-clip peaks at or above this level are severe overload (~+6 dB above the knee).
     * Native soft-clip output asymptotes to 1.0 FS; at input 2.0 the safety stage is saturated.
     */
    const val SEVERE_OVERLOAD_THRESHOLD_LINEAR = 2.0f

    private const val MIN_PEAK_LINEAR = 1e-6f

    fun indicatorLevelForPeak(peakLinear: Float, isStopped: Boolean): MasterPeakIndicatorLevel {
        if (isStopped) {
            return MasterPeakIndicatorLevel.Inactive
        }
        val peak = peakLinear.coerceAtLeast(0f)
        if (peak <= MIN_PEAK_LINEAR) {
            return MasterPeakIndicatorLevel.Green
        }
        return when {
            peak >= SEVERE_OVERLOAD_THRESHOLD_LINEAR -> MasterPeakIndicatorLevel.Red
            peak >= SOFT_CLIP_THRESHOLD_LINEAR -> MasterPeakIndicatorLevel.Yellow
            else -> MasterPeakIndicatorLevel.Green
        }
    }

    fun fromPeakHoldLinear(peakLinear: Float, isStopped: Boolean): MasterOutputMeterState {
        if (isStopped) {
            return MasterOutputMeterState()
        }
        val peak = peakLinear.coerceAtLeast(0f)
        if (peak <= MIN_PEAK_LINEAR) {
            return MasterOutputMeterState(
                peakDbText = "0 dB",
                indicatorLevel = MasterPeakIndicatorLevel.Green,
            )
        }
        val dbfs = 20.0 * kotlin.math.log10(peak.toDouble())
        return MasterOutputMeterState(
            peakDbText = formatDbfs(dbfs),
            indicatorLevel = indicatorLevelForPeak(peak, isStopped = false),
        )
    }

    private fun formatDbfs(dbfs: Double): String {
        val rounded = kotlin.math.round(dbfs * 10.0) / 10.0
        if (rounded == 0.0) {
            return "0 dB"
        }
        val magnitude = kotlin.math.abs(rounded)
        val formatted = String.format(java.util.Locale.US, "%.1f", magnitude)
        return if (rounded > 0) {
            "+$formatted dB"
        } else {
            "-$formatted dB"
        }
    }
}
