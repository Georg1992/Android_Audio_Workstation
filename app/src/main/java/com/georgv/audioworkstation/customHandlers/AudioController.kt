package com.georgv.audioworkstation.customHandlers

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.fragment.app.FragmentActivity
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.ui.main.MainFragment
import java.io.*
import kotlin.concurrent.thread

object AudioController {
    enum class ControllerState {
        REC,
        PLAY,
        PLAYREC,
        STOP,
        PAUSE
    }

    lateinit var fragmentActivitySender: FragmentActivity
    private lateinit var recorder: AudioRecord
    private lateinit var player: MediaPlayer
    private val SAMPLE_RATE = 44100
    private val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC

    //for raw audio can use
    private val RAW_AUDIO_SOURCE = MediaRecorder.AudioSource.UNPROCESSED
    private val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    private val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    private val BUFFER_SIZE_RECORDING =
        AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    private var recordingThread: Thread? = null

    var controllerState: ControllerState = ControllerState.STOP//NOT GOOD
    lateinit var lastRecorded:Track //NOT GOOD

    val readyToPlayTrackList:MutableList<Track> = mutableListOf()
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
        while (controllerState == ControllerState.REC || controllerState == ControllerState.PLAYREC) {
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
            Log.e(ContentValues.TAG, "exception while closing output stream $e")
            e.printStackTrace()
        }
        val inputFile = File(track.pcmDir)
        val outputFile = File(track.wavDir)
        TypeConverter.PCMToWAV(
            inputFile, outputFile, 2, SAMPLE_RATE, SAMPLE_RATE, 16
        )
    }


    private fun stop() {
        if (this::recorder.isInitialized) {
            recorder.release()
        }
        for (player in playerList) {
            if (this::player.isInitialized) {
                player.stop()
                player.release()
            }
        }
        playerList.clear()
        recordingThread = null
    }

    private fun playTrack(track:Track) {
        player = MediaPlayer()
        player.apply {
            setDataSource(track.wavDir)
        }
        playerList.add(player)
        try {
            player.prepare()
            player.start()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    fun changeState(audioControllerState: ControllerState) {
        controllerState = audioControllerState
        when (audioControllerState) {
            ControllerState.PLAY -> {
                for(track in readyToPlayTrackList){
                    playTrack(track)
                }
                MainFragment.setPlayView()
            }
            ControllerState.PLAYREC -> {
                startRecording()
                for(track in readyToPlayTrackList){
                    playTrack(track)
                }
                MainFragment.setRecView()
            }
            ControllerState.REC -> {
                startRecording()
                MainFragment.setRecView()
            }
            ControllerState.STOP -> {
                stop()
                MainFragment.setStopView()
            }
            ControllerState.PAUSE -> TODO()
        }
    }

}