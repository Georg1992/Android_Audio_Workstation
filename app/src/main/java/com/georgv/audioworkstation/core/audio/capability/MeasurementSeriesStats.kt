package com.georgv.audioworkstation.core.audio.capability

import kotlin.math.pow

data class MeasurementSeriesStats(
    val medianMs: Double?,
    val p95Ms: Double?,
    val minMs: Double?,
    val maxMs: Double?,
    val varianceMs: Double?,
    val confidence: Double,
    val sampleCount: Int,
) {
    companion object {
        fun fromSamples(samples: List<LatencyMeasurementSample>): MeasurementSeriesStats? {
            val values =
                samples
                    .map { it.valueMs }
                    .filter { it.isFinite() }
            if (values.isEmpty()) {
                return null
            }
            val sorted = values.sorted()
            val count = sorted.size
            val median = percentile(sorted, 0.5)
            val p95 = percentile(sorted, 0.95)
            val min = sorted.first()
            val max = sorted.last()
            val variance =
                if (count < 2) {
                    0.0
                } else {
                    val mean = sorted.average()
                    sorted.map { (it - mean).pow(2) }.average()
                }
            val confidence =
                when {
                    count >= 5 -> 0.9
                    count >= 3 -> 0.75
                    count >= 2 -> 0.6
                    else -> 0.4
                }
            return MeasurementSeriesStats(
                medianMs = median,
                p95Ms = p95,
                minMs = min,
                maxMs = max,
                varianceMs = variance,
                confidence = confidence,
                sampleCount = count,
            )
        }

        private fun percentile(sorted: List<Double>, fraction: Double): Double {
            if (sorted.isEmpty()) {
                return Double.NaN
            }
            if (sorted.size == 1) {
                return sorted.first()
            }
            val index = ((sorted.size - 1) * fraction).toInt().coerceIn(0, sorted.lastIndex)
            return sorted[index]
        }
    }
}
