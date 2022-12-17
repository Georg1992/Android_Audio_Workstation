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
import com.georgv.audioworkstation.ui.main.TrackListFragment
import java.io.*
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
    private lateinit var recorder: AudioRecord
    private val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    private lateinit var recordingThread:Thread

    //for raw audio can use
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    const val SAMPLE_RATE = 44100
    private val BUFFER_SIZE_RECORDING =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)


    var controllerState: ControllerState = ControllerState.STOP//NOT GOOD
    lateinit var lastRecorded: Track //NOT GOOD

    val playerList: MutableList<MediaPlayer> = mutableListOf()



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
                writeAudioDataToFile(lastRecorded)
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



    private fun playTrack(player: MediaPlayer) {
        try {
            val playingThread = thread(true) {
                player.prepare()
            }
            player.setOnPreparedListener{

                playingThread.interrupt()
                player.setOnCompletionListener {
                    it.stop()
                    if(allTracksComplete() && controllerState != ControllerState.PLAYREC){
                        changeState(ControllerState.STOP)
                    }
                }
                player.start()
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    private fun allTracksComplete():Boolean{
        return playerList.all { !it.isPlaying }
    }


    fun changeState(audioControllerState: ControllerState) {
        controllerState = audioControllerState
        when (audioControllerState) {
            ControllerState.PLAY -> {
                for (player in playerList) {
                    playTrack(player)
                }
                TrackListFragment.setPlayView()
            }
            ControllerState.PLAYREC -> {
                startRecording()
                for (player in playerList) {
                    playTrack(player)
                }
                TrackListFragment.setRecView()
            }
            ControllerState.REC -> {
                startRecording()
                TrackListFragment.setRecView()
            }
            ControllerState.STOP -> {
                stop()
                TrackListFragment.setStopView()
            }
            ControllerState.PAUSE -> {
                for (player in playerList) {
                    player.pause()
                }
                TrackListFragment.setPauseView()
            }
            ControllerState.CONTINUE -> {
                for(player in playerList){
                    player.start()

                }
                TrackListFragment.setPlayView()
            }
        }
    }
}

