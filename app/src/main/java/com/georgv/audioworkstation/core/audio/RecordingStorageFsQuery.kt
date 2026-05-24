package com.georgv.audioworkstation.core.audio

/** Reads available free bytes for a project recording directory path. */
fun interface RecordingStorageFsQuery {
    fun availableBytes(path: String): Long?
}
