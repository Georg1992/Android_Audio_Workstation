package com.georgv.audioworkstation.core.audio

import java.io.InputStream

/**
 * Imports an external audio file into the project's internal storage.
 *
 * Implementations are responsible for:
 *  - Opening a fresh read stream via [AudioImportSource.open].
 *  - Validating / decoding the input.
 *  - Writing a file at [destinationPath] that conforms to [target] (same sample rate, bit depth,
 *    and channel layout) so the native playback engine can play it with no further work.
 *
 * The MVP implementation only accepts PCM WAV that already matches the target format.
 * A future implementation can swap in transparent decode + resample (e.g. via MediaCodec)
 * without touching callers.
 */
interface AudioImporter {
    suspend fun import(
        source: AudioImportSource,
        destinationPath: String,
        target: AudioImportTarget
    ): AudioImportResult
}

/**
 * Platform-agnostic handle that yields a fresh [InputStream] for a source audio file.
 * Keeps platform types (e.g. `android.net.Uri`) out of the core audio interfaces so they
 * stay unit-testable on the JVM.
 */
fun interface AudioImportSource {
    fun open(): InputStream?
}

data class AudioImportTarget(
    val sampleRate: Int,
    val fileBitDepth: Int,
    val channelMode: ChannelMode
)

sealed class AudioImportResult {
    /**
     * Successful import. [durationMs] is the decoded audio length and [channelMode] is
     * the channel layout of the produced file (may differ from target if the source was mono
     * vs stereo and resampling is deferred to a future version).
     */
    data class Success(
        val durationMs: Long,
        val channelMode: ChannelMode
    ) : AudioImportResult()

    /**
     * Typed import errors. They carry only structured data; the UI layer maps them to a localized
     * [com.georgv.audioworkstation.core.ui.UiMessage] so this domain stays free of English copy.
     */
    sealed class Failure : AudioImportResult() {
        data object FileNotReadable : Failure()
        data object InvalidWav : Failure()
        data object UnsupportedEncoding : Failure()
        data object UnsupportedChannelCount : Failure()
        data class SampleRateMismatch(val expected: Int, val actual: Int) : Failure()
        data class BitDepthMismatch(val expected: Int, val actual: Int) : Failure()
        data class WriteFailed(val reason: String) : Failure()
    }
}
