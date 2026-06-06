package com.georgv.audioworkstation.core.audio.waveform

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

private const val WaveformPeakWeight = 0.35f
private const val WaveformRmsWeight = 0.65f

internal class WaveformBucketAccumulator {
    private var peak = 0f
    private var sumSquares = 0.0
    private var sampleCount = 0

    fun addSample(sample: Float) {
        val absSample = abs(sample)
        peak = max(peak, absSample)
        sumSquares += (sample * sample).toDouble()
        sampleCount++
    }

    fun visualAmplitude(): Float {
        if (sampleCount == 0) return 0f
        val rms = sqrt(sumSquares / sampleCount.toDouble()).toFloat().coerceIn(0f, 1f)
        return (WaveformPeakWeight * peak + WaveformRmsWeight * rms).coerceIn(0f, 1f)
    }
}
