package com.georgv.audioworkstation.data.repository

import android.os.SystemClock

/**
 * Optional debug hooks for [ProjectRepository]. Keeps the data layer free of UI diagnostics imports.
 */
interface ProjectRepositoryDiagnostics {
    fun onProjectsCachedEmission(projectCount: Int) = Unit

    fun onTracksObserved(projectId: String, trackCount: Int) = Unit

    fun isQuickRecordActiveFor(projectId: String): Boolean = false

    fun logQuickRecordStepStart(step: String, projectId: String) = Unit

    fun logQuickRecordStepEnd(
        step: String,
        startUptimeMs: Long,
        projectId: String,
        detail: String = "",
    ) = Unit

    suspend fun <T> traceQuickRecordSection(
        section: String,
        projectId: String,
        block: suspend () -> T,
    ): T = block()

    suspend fun timedQuickRecordDbWrite(
        operation: String,
        projectId: String?,
        write: suspend () -> Unit,
    ) {
        write()
    }

    companion object {
        val None: ProjectRepositoryDiagnostics = object : ProjectRepositoryDiagnostics {}
    }
}

internal suspend fun ProjectRepositoryDiagnostics.dbWriteWhenActive(
    operation: String,
    projectId: String?,
    write: suspend () -> Unit,
) {
    if (projectId != null && isQuickRecordActiveFor(projectId)) {
        timedQuickRecordDbWrite(operation, projectId, write)
    } else {
        write()
    }
}
