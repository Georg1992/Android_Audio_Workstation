package com.georgv.audioworkstation.ui.diagnostics

import android.util.Log
import com.georgv.audioworkstation.core.audio.MultiPlaybackSpec
import com.georgv.audioworkstation.core.audio.RecordingStopSnapshot
import com.georgv.audioworkstation.core.audio.laneSourceReadOffsetMs
import com.georgv.audioworkstation.data.db.entities.TrackEntity

/** Logcat tag shared with native TransportFrameMap diagnostics. */
object TransportFrameDiagnostics {
    private const val TAG = "TransportFrameMap"

    fun logPlaybackArm(spec: MultiPlaybackSpec, transportStartFrame: Long, transportFrame: Long) {
        if (!AudioSyncLogConfig.transportFrameVerboseEnabled) {
            return
        }
        Log.i(
            TAG,
            "kotlin_playback_arm startMs=${spec.startPositionMs} sessionEndMs=${spec.sessionTimelineEndMs} " +
                "transportStartFrame=$transportStartFrame transportFrame=$transportFrame laneCount=${spec.lanes.size}",
        )
        spec.lanes.forEachIndexed { index, lane ->
            val sourceSeekMs =
                laneSourceReadOffsetMs(
                    playheadMs = spec.startPositionMs,
                    clipStartMs = lane.timelineClipStartMs,
                    loopEnabled = lane.loopEnabled,
                    loopSourceStartMs = lane.loopSourceStartMs,
                    loopSourceEndMs = lane.loopSourceEndMs,
                    sourceTrimStartMs = lane.sourceTrimStartMs,
                )
            Log.i(
                TAG,
                "kotlin_lane_seek index=$index trackId=${lane.trackId} loop=${lane.loopEnabled} " +
                    "clipStartMs=${lane.timelineClipStartMs} sourceSeekMs=$sourceSeekMs",
            )
        }
    }

    fun logOverdubSessionArm(spec: MultiPlaybackSpec) {
        if (!AudioSyncLogConfig.transportFrameVerboseEnabled) {
            return
        }
        Log.i(
            TAG,
            "kotlin_overdub_session_arm startMs=${spec.startPositionMs} sessionEndMs=${spec.sessionTimelineEndMs} " +
                "laneCount=${spec.lanes.size}",
        )
        logPlaybackArm(spec, transportStartFrame = -1L, transportFrame = -1L)
    }

    fun logRecordingStop(snapshot: RecordingStopSnapshot) {
        if (!AudioSyncLogConfig.transportFrameVerboseEnabled) {
            return
        }
        Log.i(
            TAG,
            "kotlin_recording_stop firstSampleTransportMs=${snapshot.firstSampleTransportPositionMs} " +
                "capturedFrames=${snapshot.capturedFrameCount} capturedDurationMs=${snapshot.capturedDurationMs}",
        )
    }

    fun logFinalizedTrackPlacement(track: TrackEntity, wavFrameCount: Long?) {
        if (!AudioSyncLogConfig.transportFrameVerboseEnabled) {
            return
        }
        Log.i(
            TAG,
            "kotlin_finalized_track trackId=${track.id} timelineStartOffsetMs=${track.timelineStartOffsetMs} " +
                "durationMs=${track.duration} wavFrameCount=${wavFrameCount ?: "unknown"}",
        )
    }
}
