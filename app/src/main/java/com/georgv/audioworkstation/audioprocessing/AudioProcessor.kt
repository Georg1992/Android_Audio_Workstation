package com.georgv.audioworkstation.audioprocessing

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import com.georgv.audioworkstation.MainActivity
import com.georgv.audioworkstation.UiListener
import com.georgv.audioworkstation.audioprocessing.AudioController.controllerState
import com.georgv.audioworkstation.customHandlers.TypeConverter
import com.georgv.audioworkstation.customHandlers.WavHeader
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import org.apache.commons.io.FileUtils
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors



private const val PLAYBACK_BUFFER_SIZE = 32 * 1024
private const val PCM_16BIT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
private const val FLOAT_AUDIO_FORMAT = AudioFormat.ENCODING_PCM_FLOAT
private const val SAMPLE_RATE = 44100
//private const val CHANNELS_MONO = AudioFormat.CHANNEL_OUT_MONO
private const val CHANNELS_STEREO = AudioFormat.CHANNEL_OUT_STEREO

private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
private val BUFFER_SIZE_RECORDING =
    AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, PCM_16BIT_AUDIO_FORMAT)


class AudioProcessor():UiListener {
    private lateinit var effects:Array<Effect?>
    private lateinit var file: File
    private lateinit var audioStreamingService: AudioStreamingService

    private lateinit var _track:Track
    private var track: Track
        get() = _track
        set(value) {
            _track = value
            effects = arrayOf(TypeConverter.toEffect(track.equalizer),
                TypeConverter.toEffect(track.compressor),TypeConverter.toEffect(track.reverb))
            file = File(track.wavDir)
            volume = track.volume
        }

    private var mixprocessor = false
    private var volume = 100F
    private lateinit var _song: Song
    private var song:Song
        get() = _song
        set(value) {
            _song = value
            file = song.wavFilePath?.let { File(it) }!!
            mixprocessor = true
        }

    private var _audioTrack: AudioTrack? = null
    private var audioTrack:AudioTrack?
    get() = _audioTrack
    set(value) {
        _audioTrack=value
        controlVolume(volume)
    }
    var isPlaying = false

    private val pcm16BitBufferSize = AudioTrack.getMinBufferSize(SAMPLE_RATE,
        CHANNEL_CONFIG, PCM_16BIT_AUDIO_FORMAT)
    private val executor: ExecutorService = Executors.newFixedThreadPool(2)


    fun setTrackToProcessor(track: Track){
        this.track = track
    }

    fun setSongToProcessor(song:Song){
        this.song = song
    }

    fun startRecording() {
        if (ActivityCompat.checkSelfPermission(
                AudioController.fragmentActivitySender,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        executor.execute {
            val recorder = AudioRecord(
                AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, PCM_16BIT_AUDIO_FORMAT,
                BUFFER_SIZE_RECORDING
            )
            recorder.startRecording()
            writeAudioDataToFile(recorder)
        }
    }

    fun playAudio(){
        if(mixprocessor){
            playNoProcessing()
            return
        }
        if(effects.all{it == null}){
            playNoProcessing()
            return
        }
        playWithProcessing()
    }


    fun controlVolume(volume: Float) {
        val vol = volume / 100F
        audioTrack?.setVolume(vol)
    }



    private fun playWithProcessing() {
        executor.execute {
            try {
                createAudioTrack(PLAYBACK_BUFFER_SIZE, FLOAT_AUDIO_FORMAT, CHANNELS_STEREO)
                val inputStream = file.inputStream()
                inputStream.skip(44)
                val buffer = ByteArray(PLAYBACK_BUFFER_SIZE*2)
                audioTrack?.play()
                isPlaying = true
                var index = 0
                val nextBuffers = arrayOf(floatArrayOf(), floatArrayOf())
                inputStream.read(buffer)
                var floatBuffer = toFloatArrayMono(buffer)
                nextBuffers[0] = effectChain(floatBuffer)

                while ((inputStream.read(buffer) > 0 && (controllerState == AudioController.ControllerState.PLAY
                            || controllerState == AudioController.ControllerState.PLAY_REC))){
                    executor.execute {
                            floatBuffer = toPcmFloating(buffer)
                            nextBuffers[(index+1)%2] = effectChain(floatBuffer)
                    }
                    audioTrack?.write(nextBuffers[index%2], 0, nextBuffers[index%2].size, AudioTrack.WRITE_BLOCKING)
                    if(nextBuffers[(index+1)%2].size < PLAYBACK_BUFFER_SIZE){
                        audioTrack?.write(nextBuffers[index+1%2], 0, nextBuffers[index+1%2].size, AudioTrack.WRITE_BLOCKING)
                    }
                    index++
                }
                stopAudioTrack()
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                e.printStackTrace()
            }
        }
    }

    private fun playNoProcessing(){
        executor.execute{
            createAudioTrack(pcm16BitBufferSize, PCM_16BIT_AUDIO_FORMAT, CHANNELS_STEREO)
            val inputStream = file.inputStream()
            inputStream.skip(44)
            val buffer = ByteArray(pcm16BitBufferSize)
            audioTrack?.play()
            isPlaying = true
            while (inputStream.read(buffer) > 0 && (controllerState == AudioController.ControllerState.PLAY
                || controllerState == AudioController.ControllerState.PLAY_REC)) {
                audioTrack?.write(buffer, 0 , buffer.size)
            }
            stopAudioTrack()
            inputStream.close()
        }
    }

    private fun effectChain(floatBuffer: FloatArray):FloatArray{
        var processedSignal = floatBuffer
        for (effect in effects) {
            if (effect != null) {
                processedSignal = effect.apply(processedSignal)
            }
        }
        return processedSignal
    }


    private fun writeAudioDataToFile(recorder:AudioRecord) {
        val audioBuffer = ByteArray(BUFFER_SIZE_RECORDING)
        val outputStream: FileOutputStream?
        try {
            outputStream = FileOutputStream(file)
        } catch (e: FileNotFoundException) {
            return
        }
        while (controllerState in setOf(AudioController.ControllerState.REC, AudioController.ControllerState.PLAY_REC)) {
            recorder.read(audioBuffer, 0, BUFFER_SIZE_RECORDING)
            try {
                outputStream.write(audioBuffer)
                // clean up file writing operations
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        recorder.release()
        try {
            outputStream.flush()
            outputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val outputFileSize = file.length() + 44
        writeHeader(file, outputFileSize)
    }

    private fun stopAudioTrack() {
        isPlaying = false
        audioTrack?.stop()
        audioTrack?.flush()
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            AudioController.checkTracksFinishedPlaying()
        }
    }

    private fun shutDownExecutor() {
        executor.shutdown()
    }

    private fun createAudioTrack(bufferSize: Int, encoding: Int, channels:Int) {
        audioTrack = AudioTrack.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(encoding)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(channels)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .build()

    }


    private fun writeHeader(file: File, outputFileSize: Long) {
        val header = WavHeader(outputFileSize, SAMPLE_RATE, 2, 16)
        val headerAsByteArray = header.getHeader()
        val randomAccessFile = RandomAccessFile(file, "rw")
        randomAccessFile.seek(0) // seek to the beginning of the file
        randomAccessFile.write(headerAsByteArray)
        randomAccessFile.close()
    }

    private fun toPcmFloating(bytes: ByteArray): FloatArray {
        val floatBuffer = FloatArray(bytes.size / 2)
        val shortBuffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in floatBuffer.indices) {
            floatBuffer[i] = shortBuffer.get(i) / 32767f
        }
        return floatBuffer
    }


    private fun toFloatArrayMono(bytes: ByteArray): FloatArray {
        val sampleSize = 2 // 16-bit audio is 2 bytes per sample
        val floatBuffer = FloatBuffer.allocate(bytes.size / sampleSize)
        val byteBuffer = ByteBuffer.wrap(bytes)
        byteBuffer.order(ByteOrder.LITTLE_ENDIAN) // assuming the input is little-endian
        while (byteBuffer.remaining() >= sampleSize) {
            val sample = byteBuffer.short / 32768.0f
            floatBuffer.put(sample)
        }
        return floatBuffer.array()
    }


    //    byte | 01 02 | 03 04 | 05 06 | 07 08 | 09 10 | 11 12 | ...
//    channel |  Left | Right | Left  | Right | Left |  Right | ...
//    frame |     First     |    Second     |     Third     | ...
//    sample | 1st L | 1st R | 2nd L | 2nd R | 3rd L | 3rd R | ... etc.

    fun mixAudio(tracks: List<Track>, outputWavFile: File, callback: AudioProcessingCallback) {
        executor.execute{
            val handler = Handler(Looper.getMainLooper())
            handler.post{
                callback.onProcessingStarted()
            }
            val bufferSize = PLAYBACK_BUFFER_SIZE
            var offset: Long = 44 // Skipping the WAV header
            val outputFileSize = getLongestFileSize(tracks)

            val mixed = FloatArray(bufferSize / 2)
            val mixedShort = ShortArray(bufferSize / 2)
            val mixedByte = ByteArray(bufferSize)

            val doTimes = ((outputFileSize - offset) / bufferSize).toInt() + 1
            //writeHeader(outputWavFile, outputFileSize)
            repeat(doTimes) {
                for (track in tracks) {
                    handler.post{
                        callback.onProcessingProgress(track.trackName)
                    }
                    val inputFile = File(track.wavDir)
                    val bytesBuffer = ByteBuffer.allocateDirect(bufferSize)
                    inputFile.inputStream().channel.read(bytesBuffer, offset)
                    val bytes = bytesBuffer.array()
                    val tmpBuffer = ShortArray(bufferSize / 2)
                    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        .get(tmpBuffer)

                    val audioFloats = FloatArray(bufferSize / 2)

                    for (i in tmpBuffer.indices) {
                        audioFloats[i] = tmpBuffer[i].toFloat() / 0x8000

                        if (track == tracks[0]) {
                            mixed[i] = audioFloats[i]
                        } else {
                            mixed[i] =
                                ((mixed[i] + audioFloats[i]) * 0.8).toFloat()               //(mixed[i] + audioFloats[i]) - (mixed[i]*audioFloats[i])
                        }
                        if (mixed[i] > 1.0f) mixed[i] = 1.0f
                        if (mixed[i] < -1.0f) mixed[i] = -1.0f

                        mixedShort[i] = (mixed[i] * 32768.0f).toInt().toShort()
                    }
                }
                offset += bufferSize
                ByteBuffer.wrap(mixedByte).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    .put(mixedShort)
                FileUtils.writeByteArrayToFile(outputWavFile, mixedByte, true)

            }
            handler.post{
                callback.onProcessingFinished()
            }
        }
    }

    private fun getLongestFileSize(tracks: List<Track>): Long {
        val longestTrack = tracks.maxByOrNull { track -> track.duration!! }
        return File(longestTrack!!.wavDir).length()
    }






    override fun uiCallback() {

    }

    override fun setValueFromUi(float: Float) {
        controlVolume(float)
    }

}
interface AudioProcessingCallback {
    fun onProcessingStarted()
    fun onProcessingProgress(progress: String)
    fun onProcessingFinished()
}




//fun mixAudio(tracks: List<Track>, outputWavFile: File, callback: AudioProcessingCallback) {
//    executor.execute{
//        val handler = Handler(Looper.getMainLooper())
//        handler.post{
//            callback.onProcessingStarted()
//        }
//        val bufferSize = PLAYBACK_BUFFER_SIZE
//        var offset: Long = 44 // Skipping the WAV header
//        val outputFileSize = getLongestFileSize(tracks)
//
//        val mixed = FloatArray(bufferSize / 2)
//        val mixedShort = ShortArray(bufferSize / 2)
//        val mixedByte = ByteArray(bufferSize)
//
//        val doTimes = ((outputFileSize - offset) / bufferSize).toInt() + 1
//        //writeHeader(outputWavFile, outputFileSize)
//        repeat(doTimes) {
//            for (track in tracks) {
//                handler.post{
//                    callback.onProcessingProgress(track.trackName)
//                }
//                val inputFile = File(track.wavDir)
//                val bytesBuffer = ByteBuffer.allocateDirect(bufferSize)
//                inputFile.inputStream().channel.read(bytesBuffer, offset)
//                val bytes = bytesBuffer.array()
//                val tmpBuffer = ShortArray(bufferSize / 2)
//                ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
//                    .get(tmpBuffer)
//
//                val audioFloats = FloatArray(bufferSize / 2)
//
//                for (i in tmpBuffer.indices) {
//                    audioFloats[i] = tmpBuffer[i].toFloat() / 0x8000
//
//                    if (track == tracks[0]) {
//                        mixed[i] = audioFloats[i]
//                    } else {
//                        mixed[i] =
//                            ((mixed[i] + audioFloats[i]) * 0.8).toFloat()               //(mixed[i] + audioFloats[i]) - (mixed[i]*audioFloats[i])
//                    }
//                    if (mixed[i] > 1.0f) mixed[i] = 1.0f
//                    if (mixed[i] < -1.0f) mixed[i] = -1.0f
//
//                    mixedShort[i] = (mixed[i] * 32768.0f).toInt().toShort()
//                }
//            }
//            offset += bufferSize
//            ByteBuffer.wrap(mixedByte).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
//                .put(mixedShort)
//            FileUtils.writeByteArrayToFile(outputWavFile, mixedByte, true)
//
//        }
//        handler.post{
//            callback.onProcessingFinished()
//        }
//    }
//}










