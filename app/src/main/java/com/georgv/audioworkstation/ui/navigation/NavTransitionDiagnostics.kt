package com.georgv.audioworkstation.ui.navigation

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect

/** Temporary debug diagnostics for navigation performance (enabled in debug builds). */
object NavTransitionDiagnostics {
    const val TAG = "NavTransitionDiag"

    var loggingEnabled: Boolean = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var frameMonitorActive: Boolean = false
    private var frameMonitorDeadlineUptimeMs: Long = 0L
    private var lastFrameUptimeMs: Long = 0L
    private var maxFrameGapMs: Long = 0L
    private var slowFrameCount: Int = 0
    private var lastTransitionEnterStartUptimeMs: Long = 0L

    /** Milliseconds since the last forward/back enter transition started, or -1 if none. */
    fun millisSinceLastTransitionEnterStart(): Long {
        if (lastTransitionEnterStartUptimeMs == 0L) return -1L
        return SystemClock.uptimeMillis() - lastTransitionEnterStartUptimeMs
    }

    /** Largest inter-frame gap (ms) observed in the current/recent nav frame monitor window. */
    fun peekMaxFrameGapMs(): Long = maxFrameGapMs

    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!frameMonitorActive || !loggingEnabled) return

                val nowMs = SystemClock.uptimeMillis()
                if (lastFrameUptimeMs > 0L) {
                    val gapMs = nowMs - lastFrameUptimeMs
                    if (gapMs > maxFrameGapMs) maxFrameGapMs = gapMs
                    if (gapMs > SLOW_FRAME_THRESHOLD_MS) {
                        slowFrameCount++
                        Log.w(TAG, "slow frame gap=${gapMs}ms during nav window")
                    }
                }
                lastFrameUptimeMs = nowMs

                if (nowMs < frameMonitorDeadlineUptimeMs) {
                    Choreographer.getInstance().postFrameCallback(this)
                } else {
                    Log.d(
                        TAG,
                        "nav frame monitor finished maxGap=${maxFrameGapMs}ms " +
                            "slowFrames=$slowFrameCount window=${NAV_FRAME_MONITOR_MS}ms",
                    )
                    frameMonitorActive = false
                }
            }
        }

    fun logTransitionEnterStart(kind: String, fromRoute: String?, toRoute: String?) {
        if (!loggingEnabled) return
        lastTransitionEnterStartUptimeMs = SystemClock.uptimeMillis()
        Log.d(
            TAG,
            "transition enter start kind=$kind durationMs=$NavTransitionDurationMs " +
                "easing=$NavTransitionEasingName from=$fromRoute to=$toRoute",
        )
        startFrameMonitor()
        scheduleTransitionEnd(kind = kind, phase = "enter", fromRoute = fromRoute, toRoute = toRoute)
    }

    fun logTransitionExitStart(kind: String, fromRoute: String?, toRoute: String?) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "transition exit start kind=$kind durationMs=$NavTransitionDurationMs " +
                "easing=$NavTransitionEasingName from=$fromRoute to=$toRoute",
        )
        scheduleTransitionEnd(kind = kind, phase = "exit", fromRoute = fromRoute, toRoute = toRoute)
    }

    fun logHeavyContentRendered(destination: String, count: Int) {
        if (!loggingEnabled) return
        Log.d(TAG, "heavy content rendered destination=$destination count=$count")
    }

    @Composable
    fun MonitorDestinationLifecycle(destination: String) {
        if (!loggingEnabled) return

        DisposableEffect(destination) {
            Log.d(TAG, "compose enter route=$destination")
            onDispose {
                Log.d(TAG, "compose dispose route=$destination")
            }
        }
    }

    private fun scheduleTransitionEnd(
        kind: String,
        phase: String,
        fromRoute: String?,
        toRoute: String?,
    ) {
        if (!loggingEnabled) return
        mainHandler.postDelayed({
            if (!loggingEnabled) return@postDelayed
            Log.d(
                TAG,
                "transition $phase end kind=$kind durationMs=$NavTransitionDurationMs " +
                    "easing=$NavTransitionEasingName from=$fromRoute to=$toRoute",
            )
        }, NavTransitionDurationMs.toLong())
    }

    private fun startFrameMonitor() {
        if (!loggingEnabled) return
        frameMonitorActive = true
        maxFrameGapMs = 0L
        slowFrameCount = 0
        lastFrameUptimeMs = 0L
        frameMonitorDeadlineUptimeMs = SystemClock.uptimeMillis() + NAV_FRAME_MONITOR_MS
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private const val NAV_FRAME_MONITOR_MS = 500L
    private const val SLOW_FRAME_THRESHOLD_MS = 32L
}
