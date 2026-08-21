package com.georgv.audioworkstation.core.session

import kotlinx.coroutines.Job

/** In-memory bookkeeping for background import jobs and user-initiated cancellations. */
internal class ProjectImportSession {
    val jobs = mutableMapOf<String, Job>()
    val userCancelledTrackIds = mutableSetOf<String>()
    val cancelSelectionRollback = mutableMapOf<String, Set<String>>()

    fun cancelAllJobsAndClear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }
}
