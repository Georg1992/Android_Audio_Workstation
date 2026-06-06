package com.georgv.audioworkstation.core.audio.waveform

data class WaveformPeaks(
    val amplitudes: List<Float>,
    val leftAmplitudes: List<Float>? = null,
    val rightAmplitudes: List<Float>? = null,
    /** PCM span in the WAV used for peaks; 0 when unknown (placeholder). */
    val sourceDurationMs: Long = 0L,
) {
    val isStereo: Boolean =
        leftAmplitudes != null && rightAmplitudes != null &&
            leftAmplitudes.isNotEmpty() && rightAmplitudes.isNotEmpty()

    companion object {
        val Placeholder = WaveformPeaks(
            densifyPeaks(
                listOf(
                    0.18f, 0.28f, 0.42f, 0.34f, 0.62f, 0.74f, 0.46f, 0.32f,
                    0.54f, 0.82f, 0.66f, 0.38f, 0.24f, 0.48f, 0.72f, 0.58f,
                    0.36f, 0.22f, 0.44f, 0.68f, 0.88f, 0.64f, 0.40f, 0.30f,
                    0.52f, 0.76f, 0.60f, 0.34f, 0.20f, 0.40f, 0.56f, 0.30f,
                ),
            ),
        )
    }
}

private fun densifyPeaks(peaks: List<Float>): List<Float> {
    if (peaks.isEmpty()) return peaks
    val dense = ArrayList<Float>(peaks.size * 2)
    for (index in peaks.indices) {
        val current = peaks[index]
        val next = peaks.getOrElse(index + 1) { current }
        dense += current
        dense += (current + next) * 0.5f
    }
    return dense
}
