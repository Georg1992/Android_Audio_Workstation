package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot

/**
 * Result of [CapturePort.stopRecording].
 * Codes match native `AudioEngine::RecordingStopKind`.
 */
enum class RecordingStopKind(val code: Int) {
    NotRecording(0),
    Sealed(1),
    CaptureFailed(2),
}

/** Input capture, overdub arm, and recording stop snapshots. */
interface CapturePort {
    fun startRecording(spec: RecordingSpec, outputPath: String? = null): String?

    /**
     * Closes input and the take file.
     * [RecordingStopKind.CaptureFailed] means the file was removed and the row must not be finalized.
     */
    fun stopRecording(): RecordingStopKind

    /** True when capture hit an input or disk error and [stopRecording] has not returned yet. */
    fun isRecordingCaptureFailed(): Boolean

    fun startOverdubRecordingSession(
        playbackSpec: MultiPlaybackSpec,
        recordingSpec: RecordingSpec,
        outputPath: String,
    ): String?

    fun recordingFirstSampleTransportPositionMs(): Long

    fun recordingFirstSampleTransportFrame(): Long

    fun recordingCapturedFrameCount(): Long

    fun recordingCapturedDurationMs(): Long

    fun sessionPerceivedPlaybackOffsetMs(): Long

    fun readRecordingStopSnapshot(): RecordingStopSnapshot

    fun captureLiveSessionLatencySnapshot(): LiveSessionLatencySnapshot

    fun configureSessionTransportLatencies(
        inputLatencyMs: Double,
        outputLatencyMs: Double,
    )

    companion object {
        /** Sentinel from native when no input samples were captured. */
        const val RecordingFirstSampleTransportUnset = -1L
    }
}
