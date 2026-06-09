package com.georgv.audioworkstation.engine

data class AudioCallbackCostSnapshot(
    val callbackFrames: Int,
    val sampleCount: Long,
    val callbackMinUs: Long,
    val callbackAvgUs: Long,
    val callbackMaxUs: Long,
    val callbackP95Us: Long,
    val renderMinUs: Long,
    val renderAvgUs: Long,
    val renderMaxUs: Long,
    val renderP95Us: Long,
    val xRunCount: Int,
) {
    fun callbackBudgetMs(sampleRateHz: Int): Double? {
        if (callbackFrames <= 0 || sampleRateHz <= 0) return null
        return callbackFrames * 1000.0 / sampleRateHz.toDouble()
    }

    fun callbackCpuLoadPercent(sampleRateHz: Int): Double? {
        val budgetMs = callbackBudgetMs(sampleRateHz) ?: return null
        if (budgetMs <= 0.0) return null
        return callbackAvgUs / 1000.0 / budgetMs * 100.0
    }

    companion object {
        private const val FIELD_COUNT = 11

        fun fromNativeValues(values: LongArray?): AudioCallbackCostSnapshot? {
            if (values == null || values.size < FIELD_COUNT) return null
            return AudioCallbackCostSnapshot(
                callbackFrames = values[0].toInt(),
                sampleCount = values[1],
                callbackMinUs = values[2],
                callbackAvgUs = values[3],
                callbackMaxUs = values[4],
                callbackP95Us = values[5],
                renderMinUs = values[6],
                renderAvgUs = values[7],
                renderMaxUs = values[8],
                renderP95Us = values[9],
                xRunCount = values[10].toInt(),
            )
        }
    }
}

data class AudioInputLoopCostSnapshot(
    val readFrames: Int,
    val sampleCount: Long,
    val readBlockingMinUs: Long,
    val readBlockingAvgUs: Long,
    val readBlockingMaxUs: Long,
    val readBlockingP95Us: Long,
    val processingMinUs: Long,
    val processingAvgUs: Long,
    val processingMaxUs: Long,
    val processingP95Us: Long,
) {
    fun readBlockBudgetMs(sampleRateHz: Int): Double? {
        if (readFrames <= 0 || sampleRateHz <= 0) return null
        return readFrames * 1000.0 / sampleRateHz.toDouble()
    }

    companion object {
        private const val FIELD_COUNT = 10

        fun fromNativeValues(values: LongArray?): AudioInputLoopCostSnapshot? {
            if (values == null || values.size < FIELD_COUNT) return null
            return AudioInputLoopCostSnapshot(
                readFrames = values[0].toInt(),
                sampleCount = values[1],
                readBlockingMinUs = values[2],
                readBlockingAvgUs = values[3],
                readBlockingMaxUs = values[4],
                readBlockingP95Us = values[5],
                processingMinUs = values[6],
                processingAvgUs = values[7],
                processingMaxUs = values[8],
                processingP95Us = values[9],
            )
        }
    }
}
