package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.core.audio.latency.LiveSessionLatencySnapshot

/** Input capture, overdub arm, and recording stop snapshots. */
interface CapturePort {
    fun startRecording(spec: RecordingSpec, outputPath: String? = null): String?

    fun stopRecording(): Boolean

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
