package com.georgv.audioworkstation.online

import javax.inject.Inject
import javax.inject.Singleton

data class HeldShare(val localProjectId: String, val title: String)

@Singleton
class PendingProjectShare @Inject constructor() {
    private val lock = Any()
    private var held: HeldShare? = null

    fun hold(localProjectId: String, title: String) {
        synchronized(lock) {
            held = HeldShare(localProjectId = localProjectId, title = title)
        }
    }

    fun take(): HeldShare? =
        synchronized(lock) {
            val current = held
            held = null
            current
        }
}
