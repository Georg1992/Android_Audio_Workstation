package com.georgv.audioworkstation.core.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class TransportWaveformTraceTest {
    @Test
    fun pcm16FollowsTheSamplesAcrossTheLine() {
        val frames = TransportWaveformTrace.PointCount
        val bytes = ByteArray(frames * TransportWaveformTrace.BytesPerSample)
        for (frame in 0 until frames) {
            val sample = frame * 200
            bytes[frame * 2] = (sample and 0xff).toByte()
            bytes[frame * 2 + 1] = (sample shr 8).toByte()
        }
        val points = TransportWaveformTrace.pcm16(bytes, 0, bytes.size, channels = 1)
        assertEquals(frames, points.size)
        assertEquals(0f, points.first(), 0.0001f)
        assertEquals((frames - 1) * 200 / 32768f, points.last(), 0.0001f)
    }
}
