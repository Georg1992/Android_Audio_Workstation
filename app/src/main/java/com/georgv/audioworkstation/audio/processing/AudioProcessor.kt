package com.georgv.audioworkstation.audio.processing
import android.media.*


private const val PLAYBACK_BUFFER_SIZE = 32 * 256
private const val PCM_16BIT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val FLOAT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
private const val SAMPLE_RATE = 44100
private const val CHANNELS_MONO = AudioFormat.CHANNEL_OUT_MONO
private const val CHANNELS_STEREO = AudioFormat.CHANNEL_OUT_STEREO

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
private val BUFFER_SIZE_RECORDING =
    AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, PCM_16BIT_AUDIO_FORMAT)


class AudioProcessor() {
}





















