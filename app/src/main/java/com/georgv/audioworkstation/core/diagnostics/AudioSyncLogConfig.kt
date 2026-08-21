package com.georgv.audioworkstation.core.diagnostics

/**
 * Debug-only gate for AudioSyncDiag / TransportFrameMap verbosity during latency investigation.
 */
object AudioSyncLogConfig {
    /** Throttle clock-correlation and timestamp logs for readable overdub captures. */
    @Volatile
    var clockValidationMode: Boolean = true

    /** Per-micro-stage [PLAYBACK_STARTUP_BREAKDOWN] lines. */
    @Volatile
    var detailedStartupLogsEnabled: Boolean = false

    /** Unthrottled [INPUT_TIMESTAMP] / [OUTPUT_TIMESTAMP] on every callback/read. */
    @Volatile
    var rawTimestampSpamEnabled: Boolean = false

    /** [OVERDUB] LANE_MAP checkpoint lines. */
    @Volatile
    var laneMapLogsEnabled: Boolean = false

    /** TransportFrameMap native/kotlin placement trace spam. */
    @Volatile
    var transportFrameVerboseEnabled: Boolean = false
}
