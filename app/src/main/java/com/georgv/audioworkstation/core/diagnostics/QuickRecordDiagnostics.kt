package com.georgv.audioworkstation.core.diagnostics

import android.os.Looper
import android.os.SystemClock
import android.os.Trace
import android.util.Log

/** Debug timing for Quick Record cold-start path (enabled in debug builds only). */
object QuickRecordDiagnostics {
    const val TAG = "QuickRecordDiag"

    var loggingEnabled: Boolean = false

    @Volatile
    var quickNavigationActive: Boolean = false

    @Volatile
    var quickNavigationProjectId: String? = null

    private var clickUptimeMs: Long = 0L

    fun isActiveFor(projectId: String): Boolean =
        loggingEnabled && quickNavigationActive && quickNavigationProjectId == projectId

    fun markClickReceived() {
        if (!loggingEnabled) return
        clickUptimeMs = SystemClock.uptimeMillis()
        Trace.beginSection("QuickRecordClick")
        Trace.endSection()
        log(
            "QuickRecord click received",
            "uptimeMs=$clickUptimeMs thread=${threadLabel()}",
        )
    }

    fun markProjectIdGenerated(projectId: String) {
        if (!loggingEnabled) return
        log(
            "QuickRecord projectId generated",
            "projectId=$projectId sinceClickMs=${sinceClickMs()} thread=${threadLabel()}",
        )
    }

    fun markNavigationRequested(projectId: String) {
        if (!loggingEnabled) return
        quickNavigationActive = true
        quickNavigationProjectId = projectId
        log(
            "QuickRecord navigation requested",
            "projectId=$projectId sinceClickMs=${sinceClickMs()} thread=${threadLabel()}",
        )
    }

    fun clearQuickNavigation(projectId: String, reason: String) {
        if (!loggingEnabled) return
        if (quickNavigationProjectId != projectId) return
        log(
            "QuickRecord navigation ended",
            "projectId=$projectId reason=$reason sinceClickMs=${sinceClickMs()} " +
                "sinceNavEnterMs=${sinceNavEnterMs()}",
        )
        quickNavigationActive = false
        quickNavigationProjectId = null
    }

    fun log(step: String, detail: String = "") {
        if (!loggingEnabled) return
        val suffix =
            buildString {
                append(" sinceClickMs=").append(sinceClickMs())
                val sinceNav = sinceNavEnterMs()
                if (sinceNav >= 0L) append(" sinceNavEnterMs=").append(sinceNav)
                if (detail.isNotEmpty()) append(' ').append(detail)
            }
        Log.d(TAG, "$step$suffix")
    }

    fun logMilestone(step: String, projectId: String, detail: String = "") {
        if (!loggingEnabled) return
        if (!isActiveFor(projectId) && quickNavigationProjectId != projectId) return
        log(step, "projectId=$projectId thread=${threadLabel()} isMain=${isMainThread()} $detail")
    }

    fun logStepStart(step: String, projectId: String? = quickNavigationProjectId, detail: String = "") {
        if (!loggingEnabled) return
        if (projectId != null && !isActiveFor(projectId) && quickNavigationProjectId != projectId) return
        log("$step start", detail)
    }

    fun logStepEnd(
        step: String,
        startUptimeMs: Long,
        projectId: String? = quickNavigationProjectId,
        detail: String = "",
    ) {
        if (!loggingEnabled) return
        if (projectId != null && !isActiveFor(projectId) && quickNavigationProjectId != projectId) return
        val elapsedMs = SystemClock.uptimeMillis() - startUptimeMs
        log(
            "$step end",
            "elapsedMs=$elapsedMs thread=${threadLabel()} isMain=${isMainThread()} $detail",
        )
    }

    fun logDbWriteStart(operation: String, projectId: String? = quickNavigationProjectId) {
        if (!loggingEnabled) return
        if (projectId != null && !isActiveFor(projectId)) return
        log(
            "QuickRecord DB write start",
            "operation=$operation thread=${threadLabel()} isMain=${isMainThread()}",
        )
    }

    fun logDbWriteEnd(operation: String, startUptimeMs: Long, projectId: String? = quickNavigationProjectId) {
        if (!loggingEnabled) return
        if (projectId != null && !isActiveFor(projectId)) return
        logStepEnd("QuickRecord DB write $operation", startUptimeMs)
    }

    inline fun <T> traceSection(section: String, projectId: String? = quickNavigationProjectId, block: () -> T): T {
        if (!loggingEnabled || (projectId != null && !isActiveFor(projectId) && quickNavigationProjectId != projectId)) {
            return block()
        }
        Trace.beginSection(section)
        val startMs = SystemClock.uptimeMillis()
        try {
            return block()
        } finally {
            Trace.endSection()
            logStepEnd(section, startMs, projectId)
        }
    }

    fun sinceClickMs(): Long {
        if (clickUptimeMs == 0L) return -1L
        return SystemClock.uptimeMillis() - clickUptimeMs
    }

    fun sinceNavEnterMs(): Long = NavEnterUptime.millisSinceLastEnterStart()

    fun isMainThread(): Boolean = Looper.myLooper() == Looper.getMainLooper()

    fun threadLabel(): String = Thread.currentThread().name
}
