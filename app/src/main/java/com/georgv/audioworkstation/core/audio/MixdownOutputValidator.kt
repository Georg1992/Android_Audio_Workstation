package com.georgv.audioworkstation.core.audio

import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Guards mixdown success and Library preview against invalid or partial WAV files.
 */
@Singleton
class MixdownOutputValidator @Inject constructor() {

    /**
     * @param expectedDurationMs When null, duration is not checked (e.g. refresh from disk).
     */
    fun isValidMixdownFile(
        outputPath: String,
        projectSampleRateHz: Int,
        expectedDurationMs: Long? = null,
    ): Boolean {
        val file = File(outputPath)
        if (!file.isFile) return false

        val info = readWavPcmFileInfo(outputPath) ?: return false
        if (!info.isSupportedProjectPcm) return false
        if (info.sampleRateHz != projectSampleRateHz) return false
        if (info.frameCount <= 0L) return false
        if (file.length() < info.dataOffset + info.dataSize) return false

        if (expectedDurationMs != null) {
            val toleranceMs = durationToleranceMs(info.sampleRateHz)
            if (abs(info.durationMs - expectedDurationMs) > toleranceMs) return false
        }

        return true
    }

    private fun durationToleranceMs(sampleRateHz: Int): Long {
        val frameMs =
            if (sampleRateHz > 0) {
                1_000L / sampleRateHz.toLong()
            } else {
                1L
            }
        return maxOf(100L, frameMs * 2)
    }
}
