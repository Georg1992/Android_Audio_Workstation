package com.georgv.audioworkstation.audioprocessing

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.georgv.audioworkstation.customHandlers.TypeConverter
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.ui.main.AudioListener
import org.apache.commons.io.FileUtils
import java.io.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread


object AudioController {
    enum class ControllerState {
        REC,
        PLAY,
        CONTINUE,
        PLAYREC,
        STOP,
        PAUSE
    }

    lateinit var fragmentActivitySender: FragmentActivity
    lateinit var audioListener: AudioListener
    private lateinit var recorder: AudioRecord
    private const val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    private lateinit var recordingThread: Thread
    private lateinit var trackToRecord: Track




    //for raw audio can use
    private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val SAMPLE_RATE = 44100
    private val BUFFER_SIZE_RECORDING =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)


    var controllerState: ControllerState = ControllerState.STOP
    val playerList: MutableList<MediaPlayer> = mutableListOf()


    fun getTrackToRecord(track: Track) {
        trackToRecord = track
    }

    private fun startRecording() {
        if (ActivityCompat.checkSelfPermission(
                fragmentActivitySender,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        recorder = AudioRecord(
            AUDIO_SOURCE, SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT,
            BUFFER_SIZE_RECORDING
        )
        if (this::recorder.isInitialized) {
            recorder.startRecording()
            recordingThread = thread(true) {
                writeAudioDataToFile(trackToRecord)
            }
            recordingThread.interrupt()
        }
    }

    private fun writeAudioDataToFile(track: Track) {
        val audioBuffer = ByteArray(BUFFER_SIZE_RECORDING)
        val outputStream: FileOutputStream?
        try {
            outputStream = FileOutputStream(track.pcmDir)
        } catch (e: FileNotFoundException) {
            return
        }
        while (controllerState in setOf(ControllerState.REC, ControllerState.PLAYREC)) {
            recorder.read(audioBuffer, 0, BUFFER_SIZE_RECORDING)
            try {
                outputStream.write(audioBuffer)
                // clean up file writing operations
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        try {
            outputStream.flush()
            outputStream.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
        val inputFile = File(track.pcmDir)
        val outputFile = File(track.wavDir)

        TypeConverter.pcmToWav(
            inputFile, outputFile, 2, SAMPLE_RATE, SAMPLE_RATE, 16
        )
    }


    private fun stop() {
        if (this::recorder.isInitialized) {
            recorder.release()
        }
        for (player in playerList) {
            if (player.isPlaying) {
                player.stop()
            }
        }
    }


    private fun playAudio(player: MediaPlayer) {
        try {
            val playingThread = thread(true) {
                player.prepare()
            }
            player.setOnPreparedListener {


                playingThread.interrupt()
                player.setOnCompletionListener {
                    it.stop()
                    if (allTracksComplete() && controllerState != ControllerState.PLAYREC) {
                        changeState(ControllerState.STOP)
                    }
                }
                player.start()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun controlVolume(player:MediaPlayer?, volume:Float){
        val vol = volume/100F
        player?.setVolume(vol,vol)
    }




    private fun allTracksComplete(): Boolean {
        return playerList.all { !it.isPlaying }
    }




    fun changeState(audioControllerState: ControllerState) {
        controllerState = audioControllerState
        when (audioControllerState) {
            ControllerState.PLAY -> {
                for (player in playerList) {
                    playAudio(player)
                }
            }
            ControllerState.PLAYREC -> {
                startRecording()
                for (player in playerList) {
                    playAudio(player)
                }
            }
            ControllerState.REC -> {
                startRecording()
            }
            ControllerState.STOP -> {
                stop()
            }
            ControllerState.PAUSE -> {
                for (player in playerList) {
                    player.pause()
                }
            }
            ControllerState.CONTINUE -> {
                for (player in playerList) {
                    player.start()
                }
            }
        }
        audioListener.uiCallback()
    }


//    byte | 01 02 | 03 04 | 05 06 | 07 08 | 09 10 | 11 12 | ...
//    channel |  Left | Right | Left  | Right | Left |  Right | ...
//    frame |     First     |    Second     |     Third     | ...
//    sample | 1st L | 1st R | 2nd L | 2nd R | 3rd L | 3rd R | ... etc.

    fun mixAudio(tracks: List<Track>, outputPcmFile: File, outputWavFile: File) {
        val bufferSize = 64000
        val outputFileSize = getBigestFileSize(tracks)
        var offset: Long = 0

        val mixed = FloatArray(bufferSize / 2)
        val mixedShort = ShortArray(bufferSize / 2)
        val mixedByte = ByteArray(bufferSize)

        val doTimes = (outputFileSize / bufferSize).toInt() + 1
        repeat(doTimes) {
            for (track in tracks) {
                val inputFile = File(track.pcmDir)
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
            FileUtils.writeByteArrayToFile(outputPcmFile, mixedByte, true)
        }
        TypeConverter.pcmToWav(outputPcmFile, outputWavFile, 2, SAMPLE_RATE, SAMPLE_RATE, 16)
    }

    private fun getBigestFileSize(tracks: List<Track>): Long {
        val bigestTrack = tracks.maxByOrNull { track -> track.duration!! }
        return File(bigestTrack!!.pcmDir).length()
    }




}


