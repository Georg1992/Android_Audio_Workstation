package com.georgv.audioworkstation.core.audio.convert

import com.georgv.audioworkstation.core.audio.WavPcmFileInfo
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class AacPacketRef(val offset: Long, val length: Int)

internal class AacMixIndex(
    val sampleRateHz: Int,
    val channelCount: Int,
    val blockAlign: Int,
    val pcmBytes: Int,
    val pieceBytes: Int,
    val packets: List<AacPacketRef>,
) {
    val wavSize: Long = AacMixFile.WavHeaderBytes + pcmBytes.toLong()

    fun header(): ByteArray {
        val bytes = ByteBuffer.allocate(AacMixFile.WavHeaderBytes).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put(Riff)
        bytes.putInt(PcmPrefix + pcmBytes)
        bytes.put(Wave)
        bytes.put(Fmt)
        bytes.putInt(FmtBytes)
        bytes.putShort(PcmFormat)
        bytes.putShort(channelCount.toShort())
        bytes.putInt(sampleRateHz)
        bytes.putInt(sampleRateHz * blockAlign)
        bytes.putShort(blockAlign.toShort())
        bytes.putShort(BitsPerSample)
        bytes.put(Data)
        bytes.putInt(pcmBytes)
        return bytes.array()
    }

    private companion object {
        val Riff = "RIFF".toByteArray(Charsets.US_ASCII)
        val Wave = "WAVE".toByteArray(Charsets.US_ASCII)
        val Fmt = "fmt ".toByteArray(Charsets.US_ASCII)
        val Data = "data".toByteArray(Charsets.US_ASCII)
        const val BitsPerSample: Short = 16
        const val PcmFormat: Short = 1
        const val FmtBytes = 16
        const val PcmPrefix = 36
    }

    fun pcmLength(piece: Int): Int {
        val start = piece.toLong() * pieceBytes
        val remaining = pcmBytes.toLong() - start
        if (remaining <= 0L) error("mix is not aac")
        return minOf(pieceBytes.toLong(), remaining).toInt()
    }
}

internal object AacMixFile {
    fun write(file: File, info: WavPcmFileInfo, pieceBytes: Int, packets: List<ByteArray>) {
        if (packets.isEmpty() || pieceBytes <= 0) error("mix is not aac")
        val header = ByteBuffer.allocate(PrefixBytes + packets.size * TableEntryBytes).order(ByteOrder.LITTLE_ENDIAN)
        header.put(MagicBytes)
        header.putInt(info.sampleRateHz)
        header.putInt(info.channelCount)
        header.putInt(info.blockAlign)
        header.putInt(info.bitsPerSample)
        header.putInt(info.dataSize.toInt())
        header.putInt(pieceBytes)
        header.putInt(packets.size)
        var cursor = header.capacity()
        for (packet in packets) {
            header.putInt(cursor)
            header.putInt(packet.size)
            cursor += packet.size
        }
        FileOutputStream(file).use { out ->
            out.write(header.array())
            for (packet in packets) out.write(packet)
        }
    }

    fun read(fetch: (Long, Long) -> ByteArray): AacMixIndex {
        val head = fetch(0L, PrefixBytes - 1L)
        if (head.size < PrefixBytes || !head.copyOf(MagicBytes.size).contentEquals(MagicBytes)) error("mix is not aac")
        val rate = intAt(head, RateOffset)
        val channels = intAt(head, ChannelOffset)
        val align = intAt(head, AlignOffset)
        val bits = intAt(head, BitsOffset)
        val pcmBytes = intAt(head, PcmOffset)
        val pieceBytes = intAt(head, PieceOffset)
        val count = intAt(head, CountOffset)
        val supported = channels in 1..2 && bits == BitsPerSample && align == channels * BytesPerSample
        if (rate <= 0 || pcmBytes <= 0 || pieceBytes <= 0) error("mix is not aac")
        if (count !in 1..MaxPieces || !supported) error("mix is not aac")
        val table = fetch(PrefixBytes.toLong(), PrefixBytes + count * TableEntryBytes - 1L)
        val packets = ArrayList<AacPacketRef>(count)
        for (index in 0 until count) {
            val entry = index * TableEntryBytes
            val offset = intAt(table, entry).toLong()
            val length = intAt(table, entry + IntBytes)
            if (offset < 0L || length <= 0) error("mix is not aac")
            packets += AacPacketRef(offset, length)
        }
        return AacMixIndex(rate, channels, align, pcmBytes, pieceBytes, packets)
    }

    private fun intAt(bytes: ByteArray, offset: Int): Int {
        if (offset + IntBytes > bytes.size) error("mix is not aac")
        return ByteBuffer.wrap(bytes, offset, IntBytes).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private val MagicBytes = "AAC1".toByteArray(Charsets.US_ASCII)
    private const val PrefixBytes = 32
    private const val TableEntryBytes = 8
    private const val IntBytes = 4
    private const val RateOffset = 4
    private const val ChannelOffset = 8
    private const val AlignOffset = 12
    private const val BitsOffset = 16
    private const val PcmOffset = 20
    private const val PieceOffset = 24
    private const val CountOffset = 28
    private const val MaxPieces = 200_000
    private const val BytesPerSample = 2
    private const val BitsPerSample = 16
    const val WavHeaderBytes = 44
}
