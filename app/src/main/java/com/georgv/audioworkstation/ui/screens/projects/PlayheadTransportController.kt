package com.georgv.audioworkstation.ui.screens.projects

import com.georgv.audioworkstation.core.audio.PlaybackTransportSync
import com.georgv.audioworkstation.ui.components.TimelineMaxDurationMs
import com.georgv.audioworkstation.ui.components.TimelineMinimumBaseDurationMs
import com.georgv.audioworkstation.ui.components.timelinePlayheadClampedPositionMs
import com.georgv.audioworkstation.ui.diagnostics.ThreadingDiagnostics
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class TransportPlaybackPhase {
    Idle,
    Playing,
    Paused,
    Recording,
}

/**
 * UI playhead transport (Clock.5).
 *
 * Active engine phases ([TransportPlaybackPhase.Playing], [TransportPlaybackPhase.Recording]) advance
 * the playhead only from [nativeTransportPositionMs] via polling — no Kotlin wall-clock or elapsed-time
 * ticker. Idle/scrub and [TransportPlaybackPhase.Paused] positions are owned by Kotlin.
 */
class PlayheadTransportController(
    private val scope: CoroutineScope,
    private val playheadPositionMs: MutableStateFlow<Long>,
    private val nativeTransportPositionMs: () -> Long,
    private val pollDispatcher: CoroutineDispatcher,
    private val pollIntervalMs: Long = NATIVE_TRANSPORT_POLL_INTERVAL_MS,
    private val sessionOutputLatencyMs: () -> Double = { 0.0 },
) {
    private val _phase = MutableStateFlow(TransportPlaybackPhase.Idle)
    val phase: StateFlow<TransportPlaybackPhase> = _phase.asStateFlow()

    private var timelineBaseDurationMs: Long = TimelineMinimumBaseDurationMs
    private var pollJob: Job? = null
    private var testNativePositionOverrideMs: Long? = null
    private var playbackSeekDragActive: Boolean = false
    private var resumePlaybackAfterSeekDrag: Boolean = false

    var nativePollEnabled: Boolean = true

    fun isPlaybackSeekDragActive(): Boolean = playbackSeekDragActive

    fun setTimelineBaseDurationMs(durationMs: Long) {
        timelineBaseDurationMs = durationMs.coerceAtLeast(0L)
        when (_phase.value) {
            TransportPlaybackPhase.Recording -> Unit
            TransportPlaybackPhase.Playing -> Unit
            else ->
                playheadPositionMs.update { stored ->
                    timelinePlayheadClampedPositionMs(stored, timelineBaseDurationMs)
                }
        }
    }

    fun onPlaybackStarted(fromPositionMs: Long = 0L) {
        val startMs = timelinePlayheadClampedPositionMs(fromPositionMs, timelineBaseDurationMs)
        playheadPositionMs.value = startMs
        _phase.value = TransportPlaybackPhase.Playing
        startNativePoll()
    }

    fun onRecordingStarted(fromPositionMs: Long) {
        stopNativePoll()
        val startMs = fromPositionMs.coerceAtLeast(0L)
        playheadPositionMs.value = startMs
        _phase.value = TransportPlaybackPhase.Recording
        startNativePoll()
    }

    fun abortRecordingStart() {
        stopNativePoll()
        clearPlaybackSeekDragState()
        _phase.value = TransportPlaybackPhase.Idle
        playheadPositionMs.value = 0L
        clearTestNativeOverride()
    }

    fun enterPaused() {
        stopNativePoll()
        clearPlaybackSeekDragState()
        // Freeze last UI position; pause stops native playback and resets transport (Clock.3).
        _phase.value = TransportPlaybackPhase.Paused
    }

    fun stopAndResetToZero() {
        stopNativePoll()
        clearPlaybackSeekDragState()
        _phase.value = TransportPlaybackPhase.Idle
        playheadPositionMs.value = 0L
        clearTestNativeOverride()
    }

    fun resetWhenProjectChanges() {
        stopNativePoll()
        clearPlaybackSeekDragState()
        _phase.value = TransportPlaybackPhase.Idle
        playheadPositionMs.value = 0L
        clearTestNativeOverride()
    }

    fun abortPlaybackStart() {
        stopNativePoll()
        clearPlaybackSeekDragState()
        _phase.value = TransportPlaybackPhase.Idle
        clearTestNativeOverride()
    }

    /** Idle, Paused, and Playing (pause-on-drag seek). Recording is blocked. */
    fun canScrubPlayhead(): Boolean = _phase.value != TransportPlaybackPhase.Recording

    /** Transport Seek.1: pause engine, stop native poll, keep [TransportPlaybackPhase.Playing]. */
    fun beginPlaybackSeekDrag() {
        if (_phase.value != TransportPlaybackPhase.Playing || playbackSeekDragActive) return
        stopNativePoll()
        playbackSeekDragActive = true
        resumePlaybackAfterSeekDrag = true
    }

    fun setPlayheadDuringSeekDrag(positionMs: Long, timelineBaseDurationMs: Long) {
        val next =
            when (_phase.value) {
                TransportPlaybackPhase.Recording -> positionMs.coerceAtLeast(0L)
                TransportPlaybackPhase.Playing ->
                    timelinePlayheadClampedPositionMs(positionMs, timelineBaseDurationMs)
                else -> timelinePlayheadClampedPositionMs(positionMs, timelineBaseDurationMs)
            }
        playheadPositionMs.value = next
    }

    /** @return true when release should restart playback from the scrubbed playhead. */
    fun endPlaybackSeekDragAndConsumeResume(): Boolean {
        playbackSeekDragActive = false
        val resume = resumePlaybackAfterSeekDrag
        resumePlaybackAfterSeekDrag = false
        return resume
    }

    /** Test-only: drive playhead from a mocked native transport position when [nativePollEnabled] is false. */
    internal fun setNativeTransportPositionForTests(positionMs: Long) {
        testNativePositionOverrideMs = positionMs.coerceAtLeast(0L)
        syncPlayheadFromNative()
    }

    internal fun clearTestNativeOverride() {
        testNativePositionOverrideMs = null
    }

    private fun readNativeTransportMs(): Long =
        (testNativePositionOverrideMs ?: nativeTransportPositionMs()).coerceAtLeast(0L)

    private fun mixTransportToDisplayMs(raw: Long): Long {
        val outputLatencyMs =
            when (_phase.value) {
                TransportPlaybackPhase.Playing,
                TransportPlaybackPhase.Recording,
                -> sessionOutputLatencyMs()
                else -> 0.0
            }
        return PlaybackTransportSync.audiblePlayheadMs(
            PlaybackTransportSync.mixTransportMsFromRaw(raw),
            outputLatencyMs,
        ).value
    }

    private fun applyNativeTransportPosition(raw: Long) {
        val displayMs = mixTransportToDisplayMs(raw)
        val next =
            when (_phase.value) {
                TransportPlaybackPhase.Recording -> displayMs.coerceAtLeast(0L)
                TransportPlaybackPhase.Playing -> displayMs.coerceIn(0L, TimelineMaxDurationMs)
                TransportPlaybackPhase.Paused ->
                    timelinePlayheadClampedPositionMs(displayMs, timelineBaseDurationMs)
                else -> return
            }
        if (playheadPositionMs.value != next) {
            playheadPositionMs.value = next
        }
    }

    private fun syncPlayheadFromNative() {
        applyNativeTransportPosition(readNativeTransportMs())
    }

    private fun startNativePoll() {
        stopNativePoll()
        if (!nativePollEnabled) return
        pollJob =
            scope.launch(pollDispatcher) {
                ThreadingDiagnostics.logPollLoop("default playhead")
                while (isActive) {
                    when (_phase.value) {
                        TransportPlaybackPhase.Playing,
                        TransportPlaybackPhase.Recording,
                        -> {
                            applyNativeTransportPosition(readNativeTransportMs())
                            delay(pollIntervalMs)
                        }
                        else -> break
                    }
                }
            }
    }

    private fun stopNativePoll() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun clearPlaybackSeekDragState() {
        playbackSeekDragActive = false
        resumePlaybackAfterSeekDrag = false
    }

    companion object {
        const val NATIVE_TRANSPORT_POLL_INTERVAL_MS = 16L
    }
}
