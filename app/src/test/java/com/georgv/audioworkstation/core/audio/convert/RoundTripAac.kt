package com.georgv.audioworkstation.core.audio.convert

internal class RoundTripAac : AacCodec {
    override fun encode(pcm: ByteArray, sampleRateHz: Int, channelCount: Int): ByteArray = pcm.copyOf()

    override fun decode(packet: ByteArray, sampleRateHz: Int, channelCount: Int, pcmBytes: Int): ByteArray {
        if (packet.size < pcmBytes) error("mix is not aac")
        return packet.copyOf(pcmBytes)
    }
}
