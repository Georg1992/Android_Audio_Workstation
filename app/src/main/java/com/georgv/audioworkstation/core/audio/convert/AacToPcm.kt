package com.georgv.audioworkstation.core.audio.convert

/** Decodes a stored AAC mix into the PCM window a stream player asks for. */
class AacToPcm(private val codec: AacCodec) {
    private var url: String? = null
    private var index: AacMixIndex? = null
    private val decoded = HashMap<Int, ByteArray>()

    fun wav(address: String, start: Long, endInclusive: Long, fetch: (Long, Long) -> ByteArray): ByteArray {
        synchronized(this) {
            if (url != address) {
                url = address
                index = null
                decoded.clear()
            }
            val mix = index ?: AacMixFile.read(fetch).also { index = it }
            val last = mix.wavSize - 1L
            if (start < 0L || start > last) error("mix is not aac")
            val end = minOf(endInclusive, last)
            val out = ByteArray((end - start + 1L).toInt())
            var cursor = start
            var dest = 0
            if (cursor < AacMixFile.WavHeaderBytes) {
                val header = mix.header()
                val count = minOf(header.size - cursor.toInt(), out.size - dest)
                header.copyInto(out, dest, cursor.toInt(), cursor.toInt() + count)
                cursor += count
                dest += count
            }
            while (dest < out.size) {
                val pcmAt = cursor - AacMixFile.WavHeaderBytes
                val piece = (pcmAt / mix.pieceBytes).toInt()
                val pcm = piecePcm(fetch, mix, piece)
                val into = (pcmAt - piece.toLong() * mix.pieceBytes).toInt()
                val count = minOf(pcm.size - into, out.size - dest)
                if (count <= 0) error("mix is not aac")
                pcm.copyInto(out, dest, into, into + count)
                cursor += count
                dest += count
            }
            return out
        }
    }

    private fun piecePcm(fetch: (Long, Long) -> ByteArray, mix: AacMixIndex, piece: Int): ByteArray {
        val stored = decoded[piece]
        if (stored != null) return stored
        val ref = mix.packets.getOrNull(piece) ?: error("mix is not aac")
        val packet = fetch(ref.offset, ref.offset + ref.length - 1L)
        if (packet.size < ref.length) error("mix is not aac")
        val pcm = codec.decode(packet, mix.sampleRateHz, mix.channelCount, mix.pcmLength(piece))
        decoded[piece] = pcm
        return pcm
    }
}
