package com.georgv.audioworkstation.ui.components

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Temporary diagnostics for loop-region / recording UI regressions.
 * Filter device logs with: adb logcat -s LOOP_UI
 *
 * Set [enabled] to false once root cause is identified, then remove this file.
 */
object LoopUiDiagnostics {
    const val TAG = "LOOP_UI"

    var enabled: Boolean = true
}

internal fun WaveformState.debugTypeName(): String =
    when (this) {
        WaveformState.Loading -> "Loading"
        WaveformState.Failed -> "Failed"
        WaveformState.NoWaveform -> "NoWaveform"
        is WaveformState.Ready -> "Ready"
    }

/** TrackCard-level render decision (clip present vs fallback waveform). */
internal data class LoopUiTrackCardSnapshot(
    val trackId: String?,
    val isLoop: Boolean,
    val isRecording: Boolean,
    val hasTimelineClip: Boolean,
    val hasPersistedPlayableAudio: Boolean,
    val recordingInputLevelPresent: Boolean,
    val renderBranch: String,
    val loopRegionEditingEnabled: Boolean,
) {
    fun formatLine(): String =
        buildString {
            append("TrackCard ")
            append("trackId=").append(trackId ?: "null")
            append(" isLoop=").append(isLoop)
            append(" isRecording=").append(isRecording)
            append(" hasTimelineClip=").append(hasTimelineClip)
            append(" hasPersistedPlayableAudio=").append(hasPersistedPlayableAudio)
            append(" recordingInputLevelPresent=").append(recordingInputLevelPresent)
            append(" renderBranch=").append(renderBranch)
            append(" editingEnabled=").append(loopRegionEditingEnabled)
        }
}

/** TrackTimelineLane clip render branch (waveform, overlay, local playhead). */
internal data class LoopUiTimelineLaneSnapshot(
    val trackId: String?,
    val isLoop: Boolean,
    val isActiveRecording: Boolean,
    val hasPersistedPlayableAudio: Boolean,
    val recordingInputLevelPresent: Boolean,
    val waveformStateType: String,
    val waveformMode: TimelineLaneWaveformMode?,
    val laneLayoutResolved: Boolean,
    val loopOverlayVisible: Boolean,
    val localPlayheadVisible: Boolean,
    val editingEnabled: Boolean,
    val clipDurationMs: Long,
    val loopStartMs: Long,
    val loopEndMs: Long,
) {
    fun formatLine(): String =
        buildString {
            append("TrackTimelineLane ")
            append("trackId=").append(trackId ?: "null")
            append(" isLoop=").append(isLoop)
            append(" isActiveRecording=").append(isActiveRecording)
            append(" hasPersistedPlayableAudio=").append(hasPersistedPlayableAudio)
            append(" recordingInputLevelPresent=").append(recordingInputLevelPresent)
            append(" waveformState=").append(waveformStateType)
            append(" waveformMode=").append(waveformMode?.name ?: "null")
            append(" laneLayoutResolved=").append(laneLayoutResolved)
            append(" loopOverlayVisible=").append(loopOverlayVisible)
            append(" localPlayheadVisible=").append(localPlayheadVisible)
            append(" editingEnabled=").append(editingEnabled)
            append(" clipDurationMs=").append(clipDurationMs)
            append(" loopStartMs=").append(loopStartMs)
            append(" loopEndMs=").append(loopEndMs)
        }
}

@Composable
internal fun LoopUiLogTrackCardOnChange(snapshot: LoopUiTrackCardSnapshot) {
    if (!LoopUiDiagnostics.enabled) return
    val logKey = snapshot.trackId ?: "track_card_unknown"
    var previous by remember(logKey) { mutableStateOf<LoopUiTrackCardSnapshot?>(null) }
    SideEffect {
        if (previous != snapshot) {
            previous = snapshot
            Log.d(LoopUiDiagnostics.TAG, snapshot.formatLine())
        }
    }
}

@Composable
internal fun LoopUiLogTimelineLaneOnChange(snapshot: LoopUiTimelineLaneSnapshot) {
    if (!LoopUiDiagnostics.enabled) return
    val logKey = snapshot.trackId ?: "timeline_lane_unknown"
    var previous by remember(logKey) { mutableStateOf<LoopUiTimelineLaneSnapshot?>(null) }
    SideEffect {
        if (previous != snapshot) {
            previous = snapshot
            Log.d(LoopUiDiagnostics.TAG, snapshot.formatLine())
        }
    }
}
