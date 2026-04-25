package com.georgv.audioworkstation.core.audio

import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.ui.UiMessage

/**
 * Maps a typed [AudioImportResult.Failure] into a localizable [UiMessage] resolved by the UI.
 * Kept in the audio package next to the typed errors so adding a new failure forces updating the
 * mapping in the same place.
 */
fun AudioImportResult.Failure.toUiMessage(): UiMessage = when (this) {
    AudioImportResult.Failure.FileNotReadable ->
        UiMessage(R.string.import_failure_file_not_readable)
    AudioImportResult.Failure.InvalidWav ->
        UiMessage(R.string.import_failure_invalid_wav)
    AudioImportResult.Failure.UnsupportedEncoding ->
        UiMessage(R.string.import_failure_unsupported_encoding)
    AudioImportResult.Failure.UnsupportedChannelCount ->
        UiMessage(R.string.import_failure_unsupported_channels)
    is AudioImportResult.Failure.SampleRateMismatch ->
        UiMessage(R.string.import_failure_sample_rate_mismatch, actual, expected)
    is AudioImportResult.Failure.BitDepthMismatch ->
        UiMessage(R.string.import_failure_bit_depth_mismatch, actual, expected)
    is AudioImportResult.Failure.WriteFailed ->
        UiMessage(R.string.import_failure_write_failed, reason)
}
