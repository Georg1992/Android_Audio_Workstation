package com.georgv.audioworkstation.core.audio.capability

data class CapabilityMeasurementHistory(
    val outputFloorMs: List<LatencyMeasurementSample> = emptyList(),
    val roundTripMs: List<LatencyMeasurementSample> = emptyList(),
    val captureDelayMs: List<LatencyMeasurementSample> = emptyList(),
    val jitterMs: List<LatencyMeasurementSample> = emptyList(),
    val appOutputCostP95Us: List<LatencyMeasurementSample> = emptyList(),
    val appInputProcessingP95Us: List<LatencyMeasurementSample> = emptyList(),
    val backends: List<BackendCapabilitySnapshot> = emptyList(),
) {
    fun outputFloorStats(): MeasurementSeriesStats? = MeasurementSeriesStats.fromSamples(outputFloorMs)

    fun roundTripStats(): MeasurementSeriesStats? = MeasurementSeriesStats.fromSamples(roundTripMs)

    fun captureDelayStats(): MeasurementSeriesStats? = MeasurementSeriesStats.fromSamples(captureDelayMs)

    fun jitterStats(): MeasurementSeriesStats? = MeasurementSeriesStats.fromSamples(jitterMs)

    fun appOutputCostStats(): MeasurementSeriesStats? = MeasurementSeriesStats.fromSamples(appOutputCostP95Us)

    fun appInputProcessingStats(): MeasurementSeriesStats? =
        MeasurementSeriesStats.fromSamples(appInputProcessingP95Us)
}
