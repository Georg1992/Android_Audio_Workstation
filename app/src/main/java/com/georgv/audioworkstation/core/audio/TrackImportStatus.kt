package com.georgv.audioworkstation.core.audio

/** Import lifecycle for timeline clips. Recorded and fully imported clips use [READY]. */
enum class TrackImportStatus {
    READY,
    IMPORTING,
    FAILED,
}
