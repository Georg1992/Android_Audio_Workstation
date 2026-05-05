package com.georgv.audioworkstation.diagnostics

import android.os.Looper
import android.os.SystemClock
import android.util.Log

/**
 * Temporary tap-anchored timeline for recording startup. Filter logcat: `REC_LATENCY`.
 *
 * JVM local unit tests ship an unshadowed android.jar (SystemClock throws); [nowElapsedCompatMs]
 * falls back so production device timing stays on [SystemClock.elapsedRealtime].
 */
object RecordingLatencyTrace {

    @Volatile
    private var tapElapsedRealtimeMs: Long = 0L

    private fun nowElapsedCompatMs(): Long =
        runCatching { SystemClock.elapsedRealtime() }.getOrElse { System.nanoTime() / 1_000_000L }

    private fun threadIsMainLooperCompat(): Boolean =
        runCatching { Looper.myLooper() == Looper.getMainLooper() }.getOrDefault(false)

    fun anchorTapNow() {
        tapElapsedRealtimeMs = nowElapsedCompatMs()
        emit("tap", 0L)
    }

    fun log(label: String) {
        val anchor = tapElapsedRealtimeMs
        val now = nowElapsedCompatMs()
        val deltaMs = if (anchor == 0L) -1L else now - anchor
        emit(label, deltaMs)
    }

    private fun emit(label: String, deltaMs: Long) {
        val main = threadIsMainLooperCompat()
        val msg = if (deltaMs < 0) {
            "$label (no tap anchor)"
        } else {
            "$label: ${deltaMs}ms"
        }
        runCatching {
            Log.d("REC_LATENCY", "$msg main=$main thread=${Thread.currentThread().name}")
        }
    }
}
