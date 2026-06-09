package com.georgv.audioworkstation.core.audio.capability

/** Debug-only observe-only profiling after live overdub sessions. */
object LiveSessionProfiling {
    @Volatile
    var captureOnOverdubEnd: Boolean = false
}
