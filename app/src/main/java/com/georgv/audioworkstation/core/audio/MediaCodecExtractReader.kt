package com.georgv.audioworkstation.core.audio

import android.media.MediaExtractor
import java.nio.ByteBuffer

internal interface MediaCodecExtractReader {
    fun readSampleData(buffer: ByteBuffer, offset: Int): Int

    fun sampleTimeUs(): Long

    fun advance()
}

internal class MediaExtractorReader(
    private val extractor: MediaExtractor,
) : MediaCodecExtractReader {
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int =
        extractor.readSampleData(buffer, offset)

    override fun sampleTimeUs(): Long = extractor.sampleTime

    override fun advance() {
        extractor.advance()
    }
}
