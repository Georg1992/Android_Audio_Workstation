package com.georgv.audioworkstation.core.audio

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Routes import requests to the WAV copier or the MediaCodec decoder based on MIME type / magic bytes.
 */
@Singleton
class DelegatingAudioImporter @Inject constructor(
    private val wavAudioImporter: WavAudioImporter,
    private val mediaCodecAudioImporter: MediaCodecAudioImporter,
) : AudioImporter {

    override suspend fun import(
        source: AudioImportSource,
        destinationPath: String,
        target: AudioImportTarget,
    ): AudioImportResult =
        when (detectImportFormat(source)) {
            DetectedImportFormat.PcmWav ->
                wavAudioImporter.import(
                    source = source,
                    destinationPath = destinationPath,
                    target = target,
                )
            DetectedImportFormat.CompressedAudio -> {
                val uriSource =
                    source as? UriBackedAudioImportSource
                        ?: return AudioImportResult.Failure.UnsupportedEncoding
                mediaCodecAudioImporter.import(
                    source = uriSource,
                    destinationPath = destinationPath,
                    target = target,
                )
            }
            DetectedImportFormat.Unknown ->
                AudioImportResult.Failure.UnsupportedEncoding
        }
}
