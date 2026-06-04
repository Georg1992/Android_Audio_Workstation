package com.georgv.audioworkstation.ui.components

import com.georgv.audioworkstation.core.audio.TrackImportStatus

/** How [TrackTimelineLane] renders audio in the clip waveform area. */
enum class TimelineLaneWaveformMode {
    /** Existing extracted peaks from disk. */
    PersistedPeaks,
    /** Live input meter for a brand-new take without persisted peaks. */
    LiveRecordingMeter,
    /** Compressed import in progress with skeleton placeholder. */
    Importing,
    /** Loading / failed / no-audio placeholder. */
    Status,
}

/**
 * Active recording always uses the live input meter; persisted peaks resume after the take ends.
 */
fun timelineLaneWaveformMode(
    waveformState: WaveformState,
    importStatus: TrackImportStatus,
    isActiveRecording: Boolean,
    @Suppress("UNUSED_PARAMETER") recordingInputLevel: Float?,
): TimelineLaneWaveformMode =
    when {
        isActiveRecording -> TimelineLaneWaveformMode.LiveRecordingMeter
        importStatus == TrackImportStatus.IMPORTING ||
            waveformState is WaveformState.Importing ->
            TimelineLaneWaveformMode.Importing
        waveformState is WaveformState.Ready -> TimelineLaneWaveformMode.PersistedPeaks
        else -> TimelineLaneWaveformMode.Status
    }

/** Loop handle drags are allowed only when transport is idle. */
fun loopRegionEditingEnabled(
    playbackActive: Boolean,
    recordingActive: Boolean,
): Boolean = !playbackActive && !recordingActive
