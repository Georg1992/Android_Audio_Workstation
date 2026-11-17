package com.georgv.audioworkstation.core.audio.convert

/** AAC-LC packet codec. The packet bytes are private to the implementation. */
interface AacCodec {
    fun encode(pcm: ByteArray, sampleRateHz: Int, channelCount: Int): ByteArray

    fun decode(packet: ByteArray, sampleRateHz: Int, channelCount: Int, pcmBytes: Int): ByteArray
}
