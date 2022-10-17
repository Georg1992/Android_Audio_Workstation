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
import androidx.core.view.iterator
import androidx.fragment.app.FragmentActivity
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.TrackListAdapter
import java.io.*
import kotlin.concurrent.thread

object AudioController {
    lateinit var fragmentActivitySender:FragmentActivity
    private lateinit var recorder: AudioRecord
    private lateinit var player: MediaPlayer
    private var playerList: MutableList<MediaPlayer> = mutableListOf()
    val SAMPLE_RATE = 44100
    val AUDIO_SOURCE = MediaRecorder.AudioSource.MIC
    //for raw audio can use
    val RAW_AUDIO_SOURCE = MediaRecorder.AudioSource.UNPROCESSED
    val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_STEREO
    val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    val BUFFER_SIZE_RECORDING = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT)
    var recordingThread: Thread? = null
    var isRecordingAudio:Boolean = false


    fun startRecording(dir: String) {
        isRecordingAudio = true
        //Permissions().askForPermissions("RECORD_AUDIO", fragmentActivitySender)
        if (ActivityCompat.checkSelfPermission(
                fragmentActivitySender,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        recorder = AudioRecord(AUDIO_SOURCE,SAMPLE_RATE,CHANNEL_CONFIG,AUDIO_FORMAT,BUFFER_SIZE_RECORDING)
        recordingThread = thread(true) {
            writeAudioDataToFile(dir)
        }
    }


    fun stopPlay() {
        if(this::recorder.isInitialized){
            isRecordingAudio = false
            recorder.release()
        }
        for(player in playerList){
            if(this::player.isInitialized){
                player.stop()
                player.release()
            }
        }
        playerList.clear()
    }

    fun playTracks(mRecyclerView: RecyclerView) {
        for (view in mRecyclerView) {
            val holder =
                mRecyclerView.findContainingViewHolder(view) as TrackListAdapter.TrackViewHolder
            if (holder.selected) {
                player = MediaPlayer()
                player.apply {
                    setDataSource(holder.directory)
                }
                playerList.add(player)
                try {
                    player.prepare()
                    player.start()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun writeAudioDataToFile(dir:String) {
        val data = ByteArray(BUFFER_SIZE_RECORDING / 2)
        val outputStream: FileOutputStream?
        try {
            outputStream = FileOutputStream(dir)
        } catch (e: FileNotFoundException) {
            return
        }
        while (isRecordingAudio) {
            val read = recorder.read(data, 0, data.size)
            try {
                outputStream.write(data, 0, read)
                // clean up file writing operations
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        val file = File(dir)
        TypeConverter.PCMToWAV(file,file, 2, SAMPLE_RATE, SAMPLE_RATE,16)
        try {
            outputStream.flush()
            outputStream.close()
        } catch (e: IOException) {
            Log.e(ContentValues.TAG, "exception while closing output stream $e")
            e.printStackTrace()
        }
    }

}