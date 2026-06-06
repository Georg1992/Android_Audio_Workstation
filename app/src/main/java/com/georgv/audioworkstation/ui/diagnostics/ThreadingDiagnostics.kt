package com.georgv.audioworkstation.ui.diagnostics

import android.os.Looper
import android.util.Log

/** Lightweight threading logs for dispatcher-boundary verification (debug builds). */
object ThreadingDiagnostics {
    const val TAG = "ThreadingDiag"

    var loggingEnabled: Boolean = false

    /** Caller thread immediately before/after [withAudioIo] / [withIo] dispatches work. */
    fun logCallerBoundary(operation: String, phase: String) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "$operation caller-$phase thread=${Thread.currentThread().name} isMain=${isMainThread()}",
        )
    }

    /** Work thread inside a dispatcher block (for example before/after a native JNI call). */
    fun logWorkBoundary(operation: String, phase: String) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "$operation work-$phase thread=${Thread.currentThread().name} isMain=${isMainThread()}",
        )
    }

    fun logOperationStart(operation: String) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "$operation start thread=${Thread.currentThread().name} isMain=${isMainThread()}",
        )
    }

    fun logOperationEnd(operation: String, elapsedMs: Long) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "$operation end elapsedMs=$elapsedMs thread=${Thread.currentThread().name} " +
                "isMain=${isMainThread()}",
        )
    }

    fun logPollLoop(dispatcherLabel: String) {
        if (!loggingEnabled) return
        Log.d(
            TAG,
            "poll loop dispatcher=$dispatcherLabel thread=${Thread.currentThread().name} " +
                "isMain=${isMainThread()}",
        )
    }

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()
}
