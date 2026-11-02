package com.georgv.audioworkstation.ui.playback

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack

internal interface PcmOutput {
    fun start(sampleRateHz: Int, channelCount: Int)

    fun write(bytes: ByteArray, offset: Int, length: Int): Int

    fun pause()

    fun play()

    fun release()
}

internal class AudioTrackPcmOutput : PcmOutput {
    private var track: AudioTrack? = null

    override fun start(sampleRateHz: Int, channelCount: Int) {
        release()
        val mask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBytes = AudioTrack.getMinBufferSize(sampleRateHz, mask, AudioFormat.ENCODING_PCM_16BIT)
        if (minBytes <= 0) error("mix stream has no audio track")
        val created = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRateHz)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(mask)
                    .build(),
            )
            .setBufferSizeInBytes(minBytes)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        if (created.state != AudioTrack.STATE_INITIALIZED) {
            created.release()
            error("mix stream has no audio track")
        }
        track = created
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int): Int {
        val audio = track ?: return -1
        return audio.write(bytes, offset, length, AudioTrack.WRITE_BLOCKING)
    }

    override fun pause() {
        val audio = track ?: return
        if (audio.playState != AudioTrack.PLAYSTATE_PLAYING) return
        audio.pause()
        audio.flush()
    }

    override fun play() {
        track?.play()
    }

    override fun release() {
        track?.release()
        track = null
    }
}
