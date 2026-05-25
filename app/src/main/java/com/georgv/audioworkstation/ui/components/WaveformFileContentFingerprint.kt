package com.georgv.audioworkstation.ui.components

import java.io.File

/**
 * Stable cache key for on-disk WAV content. Same path with replaced bytes (punch splice) yields a
 * different fingerprint so waveform peaks are re-read from the final file.
 */
fun wavFileContentFingerprint(wavPath: String): String? {
    val file = File(wavPath.trim())
    if (!file.isFile) return null
    val canonicalPath =
        runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
    return "$canonicalPath|${file.lastModified()}|${file.length()}"
}

internal fun wavFilePathPrefix(fingerprint: String): String =
    fingerprint.substringBefore('|')
