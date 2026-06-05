package com.georgv.audioworkstation.core.audio

/** User-facing sample-rate label for import dialogs (matches create-project wording). */
fun formatSampleRateLabel(hz: Int): String =
    when (hz) {
        ProjectSampleRate.RATE_44_100.hz -> "44.1 kHz"
        ProjectSampleRate.RATE_48_000.hz -> "48 kHz"
        else -> "$hz Hz"
    }

fun isSupportedProjectSampleRate(hz: Int): Boolean =
    ProjectSampleRate.entries.any { it.hz == hz }
