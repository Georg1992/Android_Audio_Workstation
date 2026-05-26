package com.georgv.audioworkstation.core.audio

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Splices a temporary punch recording into an existing PCM WAV:
 * prefix (old audio + optional silence) + new recording + suffix (remaining old audio).
 *
 * Writes to [finalWavPath].tmp first, then atomically replaces [finalWavPath].
 * On failure the original track WAV is left untouched.
 */
@Singleton
class WavPunchSplicer @Inject constructor() {

    fun splice(
        originalWavPath: String,
        tempRecordingWavPath: String,
        finalWavPath: String,
        spliceStartInClipMs: Long,
        expectedSampleRateHz: Int,
        expectedBitDepth: Int = 16,
    ): WavPunchSpliceResult {
        require(expectedBitDepth == 16) { "Only 16-bit PCM punch splice is supported." }

        val recordingInfo =
            readWavPcmFileInfo(tempRecordingWavPath)
                ?: throw IOException("Temporary recording is not a readable PCM WAV.")
        validateRecordingFormat(recordingInfo, expectedSampleRateHz, expectedBitDepth)

        val originalInfo =
            originalWavPath.takeIf { it.isNotBlank() }?.let(::readWavPcmFileInfo)
        if (originalInfo != null) {
            validateOriginalMatchesRecording(originalInfo, recordingInfo)
        }

        val plan =
            buildSplicePlan(
                originalInfo = originalInfo,
                recordingInfo = recordingInfo,
                spliceStartInClipMs = spliceStartInClipMs,
            )

        val finalFile = File(finalWavPath)
        finalFile.parentFile?.mkdirs()
        val tempOut = File("$finalWavPath.tmp")
        tempOut.parentFile?.mkdirs()
        if (tempOut.exists()) tempOut.delete()

        try {
            writeSpliceOutput(
                plan = plan,
                originalWavPath = originalWavPath,
                originalInfo = originalInfo,
                tempRecordingWavPath = tempRecordingWavPath,
                recordingInfo = recordingInfo,
                tempOut = tempOut,
            )
            replaceTrackWavAtomically(tempOut, finalFile)
            File(tempRecordingWavPath).delete()

            val oldDurationMs = originalInfo?.durationMs ?: 0L
            val durationMs =
                resultingClipDurationMs(
                    oldDurationMs = oldDurationMs,
                    spliceStartInClipMs = spliceStartInClipMs.coerceAtLeast(0L),
                    recordedDurationMs = recordingInfo.durationMs,
                )

            return WavPunchSpliceResult(
                durationMs = durationMs,
                outputPath = finalFile.absolutePath,
            )
        } catch (error: Exception) {
            tempOut.delete()
            throw error
        }
    }

    private data class SplicePlan(
        val recordStartFrame: Long,
        val oldTotalFrames: Long,
        val recordedFrames: Long,
        val prefixFrames: Long,
        val suffixStartFrame: Long,
        val suffixFrames: Long,
        val totalDataBytes: Int,
        val channelCount: Int,
        val sampleRateHz: Int,
        val blockAlign: Int,
    )

    private fun buildSplicePlan(
        originalInfo: WavPcmFileInfo?,
        recordingInfo: WavPcmFileInfo,
        spliceStartInClipMs: Long,
    ): SplicePlan {
        val recordStartFrame =
            msToFramePosition(spliceStartInClipMs.coerceAtLeast(0L), recordingInfo.sampleRateHz)
        val oldTotalFrames = originalInfo?.frameCount ?: 0L
        val recordedFrames = recordingInfo.frameCount
        val prefixFrames = recordStartFrame
        val suffixStartFrame = recordStartFrame + recordedFrames
        val suffixFrames =
            if (originalInfo != null && suffixStartFrame < oldTotalFrames) {
                oldTotalFrames - suffixStartFrame
            } else {
                0L
            }
        val totalFrames = prefixFrames + recordedFrames + suffixFrames
        return SplicePlan(
            recordStartFrame = recordStartFrame,
            oldTotalFrames = oldTotalFrames,
            recordedFrames = recordedFrames,
            prefixFrames = prefixFrames,
            suffixStartFrame = suffixStartFrame,
            suffixFrames = suffixFrames,
            totalDataBytes = (totalFrames * recordingInfo.blockAlign).toInt(),
            channelCount = recordingInfo.channelCount,
            sampleRateHz = recordingInfo.sampleRateHz,
            blockAlign = recordingInfo.blockAlign,
        )
    }

    private fun writeSpliceOutput(
        plan: SplicePlan,
        originalWavPath: String,
        originalInfo: WavPcmFileInfo?,
        tempRecordingWavPath: String,
        recordingInfo: WavPcmFileInfo,
        tempOut: File,
    ) {
        FileOutputStream(tempOut).use { out ->
            writePcm16WavHeader(
                out = out,
                channelCount = plan.channelCount,
                sampleRateHz = plan.sampleRateHz,
                dataSizeBytes = plan.totalDataBytes,
            )
            writePrefixSection(
                out = out,
                plan = plan,
                originalWavPath = originalWavPath,
                originalInfo = originalInfo,
            )
            copyPcmFrames(
                sourcePath = tempRecordingWavPath,
                sourceInfo = recordingInfo,
                startFrame = 0L,
                frameCount = plan.recordedFrames,
                out = out,
            )
            if (plan.suffixFrames > 0L && originalInfo != null) {
                copyPcmFrames(
                    sourcePath = originalWavPath,
                    sourceInfo = originalInfo,
                    startFrame = plan.suffixStartFrame,
                    frameCount = plan.suffixFrames,
                    out = out,
                )
            }
        }
    }

    private fun writePrefixSection(
        out: FileOutputStream,
        plan: SplicePlan,
        originalWavPath: String,
        originalInfo: WavPcmFileInfo?,
    ) {
        if (originalInfo != null && plan.recordStartFrame <= plan.oldTotalFrames) {
            copyPcmFrames(
                sourcePath = originalWavPath,
                sourceInfo = originalInfo,
                startFrame = 0L,
                frameCount = plan.prefixFrames,
                out = out,
            )
            return
        }
        if (originalInfo != null) {
            copyPcmFrames(
                sourcePath = originalWavPath,
                sourceInfo = originalInfo,
                startFrame = 0L,
                frameCount = plan.oldTotalFrames,
                out = out,
            )
            writeSilentFrames(
                out = out,
                frameCount = plan.recordStartFrame - plan.oldTotalFrames,
                blockAlign = plan.blockAlign,
            )
            return
        }
        if (plan.recordStartFrame > 0L) {
            writeSilentFrames(
                out = out,
                frameCount = plan.recordStartFrame,
                blockAlign = plan.blockAlign,
            )
        }
    }

    private fun replaceTrackWavAtomically(tempOut: File, finalFile: File) {
        if (finalFile.exists() && !finalFile.delete()) {
            throw IOException("Failed to replace existing track WAV.")
        }
        if (!tempOut.renameTo(finalFile)) {
            throw IOException("Failed to atomically replace track WAV.")
        }
    }

    private fun validateRecordingFormat(
        info: WavPcmFileInfo,
        expectedSampleRateHz: Int,
        expectedBitDepth: Int,
    ) {
        val issue =
            when {
                !info.isSupportedProjectPcm ->
                    "Temporary recording uses unsupported PCM layout."
                info.sampleRateHz != expectedSampleRateHz ->
                    "Temporary recording sample rate does not match project."
                info.bitsPerSample != expectedBitDepth ->
                    "Temporary recording bit depth does not match project."
                else -> null
            }
        if (issue != null) {
            throw IOException(issue)
        }
    }

    private fun validateOriginalMatchesRecording(
        original: WavPcmFileInfo,
        recording: WavPcmFileInfo,
    ) {
        if (!original.isSupportedProjectPcm) {
            throw IOException("Original track WAV uses unsupported PCM layout.")
        }
        if (original.channelCount != recording.channelCount ||
            original.sampleRateHz != recording.sampleRateHz ||
            original.bitsPerSample != recording.bitsPerSample
        ) {
            throw IOException("Original and temporary recording formats do not match.")
        }
    }

    private fun copyPcmFrames(
        sourcePath: String,
        sourceInfo: WavPcmFileInfo,
        startFrame: Long,
        frameCount: Long,
        out: FileOutputStream,
    ) {
        if (frameCount <= 0L) return
        val byteCount = frameCount * sourceInfo.blockAlign
        RandomAccessFile(File(sourcePath), "r").use { source ->
            source.seek(sourceInfo.dataOffset + startFrame * sourceInfo.blockAlign)
            val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
            var remaining = byteCount
            while (remaining > 0) {
                val toRead = minOf(remaining, buffer.size.toLong()).toInt()
                source.readFully(buffer, 0, toRead)
                out.write(buffer, 0, toRead)
                remaining -= toRead
            }
        }
    }

    private fun writeSilentFrames(
        out: FileOutputStream,
        frameCount: Long,
        blockAlign: Int,
    ) {
        if (frameCount <= 0L) return
        val buffer = ByteArray(DEFAULT_COPY_BUFFER_BYTES)
        var remaining = frameCount * blockAlign
        while (remaining > 0) {
            val toWrite = minOf(remaining, buffer.size.toLong()).toInt()
            out.write(buffer, 0, toWrite)
            remaining -= toWrite
        }
    }

    private fun writePcm16WavHeader(
        out: FileOutputStream,
        channelCount: Int,
        sampleRateHz: Int,
        dataSizeBytes: Int,
    ) {
        out.writeAscii("RIFF")
        out.writeUInt32Le(PCM16_WAV_RIFF_CHUNK_SIZE + dataSizeBytes)
        out.writeAscii("WAVE")
        out.writeAscii("fmt ")
        out.writeUInt32Le(PCM16_FMT_CHUNK_SIZE)
        out.writeUInt16Le(1)
        out.writeUInt16Le(channelCount)
        out.writeUInt32Le(sampleRateHz)
        out.writeUInt32Le(sampleRateHz * channelCount * 2)
        out.writeUInt16Le(channelCount * 2)
        out.writeUInt16Le(16)
        out.writeAscii("data")
        out.writeUInt32Le(dataSizeBytes)
    }

    private companion object {
        const val DEFAULT_COPY_BUFFER_BYTES = 16 * 1024
        const val PCM16_WAV_RIFF_CHUNK_SIZE = 36
        const val PCM16_FMT_CHUNK_SIZE = 16
    }
}

private fun FileOutputStream.writeAscii(value: String) {
    write(value.toByteArray(Charsets.US_ASCII))
}

private fun FileOutputStream.writeUInt16Le(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
}

private fun FileOutputStream.writeUInt32Le(value: Int) {
    write(value and 0xFF)
    write((value shr 8) and 0xFF)
    write((value shr 16) and 0xFF)
    write((value shr 24) and 0xFF)
}
