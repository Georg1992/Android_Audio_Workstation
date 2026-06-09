package com.georgv.audioworkstation.engine

/** Native Oboe stream capability probe (read-only). */
data class OboeStreamCapabilityProbe(
    val sampleRateHz: Int,
    val channelCount: Int,
    val framesPerBurst: Int,
    val bufferCapacityInFrames: Int,
    val bufferSizeInFrames: Int,
    val performanceModeActual: Int,
    val sharingModeActual: Int,
    val audioSessionId: Int,
    val audioApi: Int,
    val format: Int,
    val estimatedStreamLatencyMs: Double?,
    val timestampAvailable: Boolean,
    val timestampStable: Boolean,
    val blockFrames: Int,
    val xRunCount: Int,
) {
    companion object {
        fun fromNativeValues(values: LongArray?): OboeStreamCapabilityProbe? {
            if (values == null || values.size < 15) return null
            val latencyMicro = values[10]
            return OboeStreamCapabilityProbe(
                sampleRateHz = values[0].toInt(),
                channelCount = values[1].toInt(),
                framesPerBurst = values[2].toInt(),
                bufferCapacityInFrames = values[3].toInt(),
                bufferSizeInFrames = values[4].toInt(),
                performanceModeActual = values[5].toInt(),
                sharingModeActual = values[6].toInt(),
                audioSessionId = values[7].toInt(),
                audioApi = values[8].toInt(),
                format = values[9].toInt(),
                estimatedStreamLatencyMs =
                    if (latencyMicro >= 0L) {
                        latencyMicro / 1000.0
                    } else {
                        null
                    },
                timestampAvailable = values[11] != 0L,
                timestampStable = values[12] != 0L,
                blockFrames = values[13].toInt(),
                xRunCount = values[14].toInt(),
            )
        }
    }
}

data class SoftwareBufferProfile(
    val ringDurationSeconds: Int,
    val prerollWallMs: Int,
    val ioBatchFrames: Int,
    val inputReadFrames: Int,
    val ioIdleSleepMs: Int,
    val inputReadTimeoutMs: Int,
) {
    companion object {
        fun fromNativeValues(values: LongArray?): SoftwareBufferProfile? {
            if (values == null || values.size < 6) return null
            return SoftwareBufferProfile(
                ringDurationSeconds = values[0].toInt(),
                prerollWallMs = values[1].toInt(),
                ioBatchFrames = values[2].toInt(),
                inputReadFrames = values[3].toInt(),
                ioIdleSleepMs = values[4].toInt(),
                inputReadTimeoutMs = values[5].toInt(),
            )
        }
    }
}
