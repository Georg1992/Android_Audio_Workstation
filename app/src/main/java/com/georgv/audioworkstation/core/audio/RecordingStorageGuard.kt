package com.georgv.audioworkstation.core.audio

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RecordingStorageGuard @Inject constructor(
    private val fsQuery: RecordingStorageFsQuery,
) {
    private val reserveBytes: Long = DEFAULT_RESERVE_BYTES
    fun availableBytes(projectDirectoryPath: String): Long? =
        fsQuery.availableBytes(projectDirectoryPath)

    fun canStartRecording(projectDirectoryPath: String): Boolean {
        val available = availableBytes(projectDirectoryPath) ?: return false
        return hasReserveRemaining(available)
    }

    fun hasReserveRemaining(availableBytes: Long): Boolean = availableBytes > reserveBytes

    fun usableBytesAfterReserve(availableBytes: Long): Long =
        (availableBytes - reserveBytes).coerceAtLeast(0L)

    fun pcmBytesPerSecond(sampleRate: Int, channelCount: Int, bitDepth: Int): Long {
        require(sampleRate > 0) { "sampleRate must be positive." }
        require(channelCount > 0) { "channelCount must be positive." }
        require(bitDepth > 0) { "bitDepth must be positive." }
        val bytesPerSample = bitDepth / 8
        return sampleRate.toLong() * channelCount * bytesPerSample
    }

    fun estimatedRemainingRecordingMs(
        availableBytes: Long,
        sampleRate: Int,
        channelCount: Int,
        bitDepth: Int,
    ): Long {
        val bytesPerSecond = pcmBytesPerSecond(sampleRate, channelCount, bitDepth)
        if (bytesPerSecond <= 0L) return 0L
        return (usableBytesAfterReserve(availableBytes) / bytesPerSecond) * 1_000L
    }

    companion object {
        const val DEFAULT_RESERVE_BYTES = 500L * 1024L * 1024L
        const val MONITOR_POLL_INTERVAL_MS = 1_500L
    }
}
