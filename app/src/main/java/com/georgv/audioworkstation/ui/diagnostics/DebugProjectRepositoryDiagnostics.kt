package com.georgv.audioworkstation.ui.diagnostics

import android.os.SystemClock
import com.georgv.audioworkstation.core.diagnostics.QuickRecordDiagnostics
import com.georgv.audioworkstation.data.repository.ProjectRepositoryDiagnostics
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebugProjectRepositoryDiagnostics @Inject constructor() : ProjectRepositoryDiagnostics {

    override fun onProjectsCachedEmission(projectCount: Int) {
        if (QuickRecordDiagnostics.loggingEnabled && QuickRecordDiagnostics.quickNavigationActive) {
            val emissionStartMs = SystemClock.uptimeMillis()
            QuickRecordDiagnostics.logStepStart(
                "ProjectRepository projects emission",
                detail = "count=$projectCount duringQuickNav=true",
            )
            QuickRecordDiagnostics.logStepEnd(
                "ProjectRepository projects emission",
                emissionStartMs,
                detail = "count=$projectCount thread=${QuickRecordDiagnostics.threadLabel()} " +
                    "isMain=${QuickRecordDiagnostics.isMainThread()} duringQuickNav=true",
            )
        }
    }

    override fun onTracksObserved(projectId: String, trackCount: Int) {
        if (!QuickRecordDiagnostics.isActiveFor(projectId)) return
        QuickRecordDiagnostics.traceSection("QuickProjectTracksEmission", projectId) {
            QuickRecordDiagnostics.log(
                "ProjectRepository tracks emission",
                "projectId=$projectId count=$trackCount " +
                    "thread=${QuickRecordDiagnostics.threadLabel()} " +
                    "isMain=${QuickRecordDiagnostics.isMainThread()} duringQuickNav=true",
            )
        }
    }

    override fun isQuickRecordActiveFor(projectId: String): Boolean =
        QuickRecordDiagnostics.isActiveFor(projectId)

    override fun logQuickRecordStepStart(step: String, projectId: String) {
        QuickRecordDiagnostics.logStepStart(step, projectId)
    }

    override fun logQuickRecordStepEnd(
        step: String,
        startUptimeMs: Long,
        projectId: String,
        detail: String,
    ) {
        QuickRecordDiagnostics.logStepEnd(step, startUptimeMs, projectId, detail)
    }

    override suspend fun <T> traceQuickRecordSection(
        section: String,
        projectId: String,
        block: suspend () -> T,
    ): T {
        if (!QuickRecordDiagnostics.loggingEnabled ||
            (!QuickRecordDiagnostics.isActiveFor(projectId) &&
                QuickRecordDiagnostics.quickNavigationProjectId != projectId)
        ) {
            return block()
        }
        val startMs = SystemClock.uptimeMillis()
        return try {
            block()
        } finally {
            QuickRecordDiagnostics.logStepEnd(section, startMs, projectId)
        }
    }

    override suspend fun timedQuickRecordDbWrite(
        operation: String,
        projectId: String?,
        write: suspend () -> Unit,
    ) {
        val dbStartMs = SystemClock.uptimeMillis()
        QuickRecordDiagnostics.logDbWriteStart(operation, projectId)
        write()
        QuickRecordDiagnostics.logDbWriteEnd(operation, dbStartMs, projectId)
    }
}
