package com.georgv.audioworkstation.ui.components

/** How [TrackTimelineLane] renders audio in the clip waveform area. */
enum class TimelineLaneWaveformMode {
    /** Existing extracted peaks from disk. */
    PersistedPeaks,
    /** Live input meter for a brand-new take without persisted peaks. */
    LiveRecordingMeter,
    /** Loading / failed / no-audio placeholder. */
    Status,
}

/**
 * Persisted peaks always win when ready; live meter is only for active recording without peaks.
 */
fun timelineLaneWaveformMode(
    waveformState: WaveformState,
    isActiveRecording: Boolean,
    recordingInputLevel: Float?,
): TimelineLaneWaveformMode =
    when {
        waveformState is WaveformState.Ready -> TimelineLaneWaveformMode.PersistedPeaks
        isActiveRecording && recordingInputLevel != null ->
            TimelineLaneWaveformMode.LiveRecordingMeter
        else -> TimelineLaneWaveformMode.Status
    }

/** Loop handle drags are allowed only when transport is idle. */
fun loopRegionEditingEnabled(
    playbackActive: Boolean,
    recordingActive: Boolean,
): Boolean = !playbackActive && !recordingActive
