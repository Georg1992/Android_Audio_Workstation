package com.georgv.audioworkstation.core.audio.convert

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal class MediaCodecAac : AacCodec {
    override fun encode(pcm: ByteArray, sampleRateHz: Int, channelCount: Int): ByteArray {
        val codec = MediaCodec.createEncoderByType(Mime)
        var running = false
        try {
            codec.configure(format(sampleRateHz, channelCount), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            running = true
            val padded = flushPad(pcm, channelCount)
            val encoded = pull(codec, padded, channelCount * BytesPerSample, sampleRateHz)
            codec.stop()
            running = false
            return packet(encoded)
        } finally {
            if (running) codec.stop()
            codec.release()
        }
    }

    override fun decode(packet: ByteArray, sampleRateHz: Int, channelCount: Int, pcmBytes: Int): ByteArray {
        val parsed = parsed(packet)
        val codec = MediaCodec.createDecoderByType(Mime)
        var running = false
        try {
            codec.configure(decodeFormat(sampleRateHz, channelCount, parsed.csd), null, null, 0)
            codec.start()
            running = true
            val pcm = pullDecoded(codec, parsed.frames, sampleRateHz)
            codec.stop()
            running = false
            return trim(pcm, parsed.delaySamples, channelCount * BytesPerSample, pcmBytes)
        } finally {
            if (running) codec.stop()
            codec.release()
        }
    }

    private fun pull(codec: MediaCodec, pcm: ByteArray, blockAlign: Int, sampleRateHz: Int): Encoded {
        var delay = 0
        val csd = ArrayList<ByteArray>()
        val frames = ArrayList<ByteArray>()
        var queued = 0
        var inputDone = false
        var outputDone = false
        var waits = 0
        val info = MediaCodec.BufferInfo()
        while (!outputDone) {
            if (!inputDone) {
                val step = queue(codec, pcm, queued, blockAlign, sampleRateHz)
                queued = step.next
                inputDone = step.done
            }
            val drained = drain(codec, info)
            if (drained == null) {
                waits += 1
                if (waits > WaitLimit) error("mix is not aac")
                continue
            }
            waits = 0
            delay = drained.delay ?: delay
            if (drained.csd) csd += drained.bytes else if (drained.bytes.isNotEmpty()) frames += drained.bytes
            outputDone = drained.ended
        }
        if (csd.isEmpty() || frames.isEmpty()) error("mix is not aac")
        val priming = if (delay > 0) delay else EncoderDelayFrames
        return Encoded(priming, csd.first(), frames)
    }

    private fun queue(codec: MediaCodec, pcm: ByteArray, queued: Int, blockAlign: Int, sampleRateHz: Int): Queued {
        val index = codec.dequeueInputBuffer(TimeoutUs)
        if (index < 0) return Queued(queued, done = false)
        val buffer = codec.getInputBuffer(index) ?: error("mix is not aac")
        buffer.clear()
        if (queued >= pcm.size) {
            val time = queued / blockAlign * UsPerSecond / sampleRateHz
            codec.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return Queued(queued, done = true)
        }
        val count = minOf(buffer.remaining(), pcm.size - queued)
        buffer.put(pcm, queued, count)
        val time = queued / blockAlign * UsPerSecond / sampleRateHz
        codec.queueInputBuffer(index, 0, count, time, 0)
        return Queued(queued + count, done = false)
    }

    private fun drain(codec: MediaCodec, info: MediaCodec.BufferInfo): Drained? {
        val index = codec.dequeueOutputBuffer(info, TimeoutUs)
        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) return null
        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
            return Drained(ByteArray(0), csd = false, ended = false, delay = delayOf(codec.outputFormat))
        }
        if (index < 0) return null
        val bytes = copy(codec, index, info)
        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val ended = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        codec.releaseOutputBuffer(index, false)
        return Drained(bytes, csd = config, ended = ended, delay = null)
    }

    private fun pullDecoded(codec: MediaCodec, frames: List<ByteArray>, sampleRateHz: Int): ByteArray {
        val pcm = ArrayList<ByteArray>()
        var frame = 0
        var inputDone = false
        var outputDone = false
        var waits = 0
        val info = MediaCodec.BufferInfo()
        while (!outputDone) {
            if (!inputDone) {
                val step = queueFrame(codec, frames, frame, sampleRateHz)
                frame = step.index
                inputDone = step.done
            }
            val drained = drain(codec, info)
            if (drained == null) {
                waits += 1
                if (waits > WaitLimit) error("mix is not aac")
                continue
            }
            waits = 0
            if (!drained.csd && drained.bytes.isNotEmpty()) pcm += drained.bytes
            outputDone = drained.ended
        }
        return join(pcm)
    }

    private fun queueFrame(codec: MediaCodec, frames: List<ByteArray>, frame: Int, sampleRateHz: Int): QueuedFrame {
        val index = codec.dequeueInputBuffer(TimeoutUs)
        if (index < 0) return QueuedFrame(frame, done = false)
        val buffer = codec.getInputBuffer(index) ?: error("mix is not aac")
        buffer.clear()
        if (frame >= frames.size) {
            codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return QueuedFrame(frame, done = true)
        }
        val bytes = frames[frame]
        buffer.put(bytes)
        val time = frame.toLong() * AacFrameSamples * UsPerSecond / sampleRateHz
        codec.queueInputBuffer(index, 0, bytes.size, time, 0)
        return QueuedFrame(frame + 1, done = false)
    }

    private fun flushPad(pcm: ByteArray, channelCount: Int): ByteArray {
        val extra = FlushFrames * channelCount * BytesPerSample
        val padded = ByteArray(pcm.size + extra)
        pcm.copyInto(padded)
        return padded
    }

    private fun trim(pcm: ByteArray, delaySamples: Int, blockAlign: Int, pcmBytes: Int): ByteArray {
        val start = delaySamples * blockAlign
        if (start < 0 || pcm.size < pcmBytes || start > pcm.size - pcmBytes) error("mix is not aac")
        return pcm.copyOfRange(start, start + pcmBytes)
    }

    private fun format(sampleRateHz: Int, channelCount: Int): MediaFormat {
        val format = MediaFormat.createAudioFormat(Mime, sampleRateHz, channelCount)
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        format.setInteger(MediaFormat.KEY_BIT_RATE, BitRatePerChannel * channelCount)
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, InputBytes)
        return format
    }

    private fun decodeFormat(sampleRateHz: Int, channelCount: Int, csd: ByteArray): MediaFormat {
        val format = MediaFormat.createAudioFormat(Mime, sampleRateHz, channelCount)
        format.setByteBuffer(Csd, ByteBuffer.wrap(csd))
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, InputBytes)
        return format
    }

    private fun delayOf(format: MediaFormat): Int {
        if (!format.containsKey(MediaFormat.KEY_ENCODER_DELAY)) return 0
        return format.getInteger(MediaFormat.KEY_ENCODER_DELAY)
    }

    private fun copy(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): ByteArray {
        val buffer = codec.getOutputBuffer(index) ?: error("mix is not aac")
        val bytes = ByteArray(info.size)
        if (info.size == 0) return bytes
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        buffer.get(bytes)
        return bytes
    }

    private fun packet(encoded: Encoded): ByteArray {
        var size = IntBytes * 2 + encoded.csd.size + IntBytes
        for (frame in encoded.frames) size += IntBytes + frame.size
        val bytes = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(encoded.delaySamples)
        bytes.putInt(encoded.csd.size)
        bytes.put(encoded.csd)
        bytes.putInt(encoded.frames.size)
        for (frame in encoded.frames) {
            bytes.putInt(frame.size)
            bytes.put(frame)
        }
        return bytes.array()
    }

    private fun parsed(packet: ByteArray): Parsed {
        val view = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val delay = view.int
        val csd = ByteArray(view.int)
        view.get(csd)
        val count = view.int
        if (count <= 0 || csd.isEmpty()) error("mix is not aac")
        val frames = ArrayList<ByteArray>(count)
        for (index in 0 until count) {
            val frame = ByteArray(view.int)
            if (frame.isEmpty()) error("mix is not aac")
            view.get(frame)
            frames += frame
        }
        return Parsed(delay, csd, frames)
    }

    private fun join(parts: List<ByteArray>): ByteArray {
        val out = ByteArray(parts.sumOf { it.size })
        var offset = 0
        for (part in parts) {
            part.copyInto(out, offset)
            offset += part.size
        }
        return out
    }

    private class Encoded(val delaySamples: Int, val csd: ByteArray, val frames: List<ByteArray>)

    private class Parsed(val delaySamples: Int, val csd: ByteArray, val frames: List<ByteArray>)

    private class Queued(val next: Int, val done: Boolean)

    private class QueuedFrame(val index: Int, val done: Boolean)

    private class Drained(val bytes: ByteArray, val csd: Boolean, val ended: Boolean, val delay: Int?)

    private companion object {
        const val Mime = "audio/mp4a-latm"
        const val Csd = "csd-0"
        const val BitRatePerChannel = 128_000
        const val InputBytes = 16_384
        const val TimeoutUs = 10_000L
        const val WaitLimit = 200
        const val UsPerSecond = 1_000_000L
        const val AacFrameSamples = 1_024L
        const val BytesPerSample = 2
        const val FlushFrames = 8_192
        const val EncoderDelayFrames = 2_048
        const val IntBytes = 4
    }
}
