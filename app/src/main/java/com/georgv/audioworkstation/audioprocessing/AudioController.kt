package com.georgv.audioworkstation.audioprocessing

import android.util.Log
import androidx.fragment.app.FragmentActivity
import com.georgv.audioworkstation.UiListener
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.ui.main.AudioListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

object AudioController {
    enum class ControllerState {
        REC,
        PLAY,
        CONTINUE,
        PLAY_REC,
        STOP,
        PAUSE
    }

    lateinit var fragmentActivitySender: FragmentActivity
    lateinit var audioListener: AudioListener
    private val mainExecutor = Executors.newSingleThreadExecutor()

    var controllerState: ControllerState = ControllerState.STOP
    lateinit var trackToRecord: Track
    val trackList: MutableList<Pair<Track, AudioProcessor>> = mutableListOf()
    var songToPlay: Pair<Song,AudioProcessor>? = null

    private fun recordAudio(track: Track) {
        val processor = AudioProcessor()
        processor.setTrackToProcessor(track)
        processor.startRecording()
    }

    @Synchronized
    fun addTrackToTheTrackList(track: Track,processor: AudioProcessor?):UiListener? {
        if(processor != null){
            val pair = Pair(track, processor)
            trackList.add(pair)
            return processor
        }
        return null
    }

    @Synchronized
    fun removeTrackFromTheTrackList(track: Track) {
        val copy = trackList.toMutableList()
        for (pair in copy) {
            if (pair.first.id == track.id) {
                trackList.remove(pair)
            }
        }
        Log.d("REMOVING TRACK", "${track.trackName} and listSize is ${trackList.size}")
    }

    fun checkTracksFinishedPlaying(){
        for(pair in trackList){
            if(pair.second.isPlaying){
                return
            }
        }
        if(controllerState != ControllerState.PLAY_REC){
            changeState(ControllerState.STOP)
        }
    }

    fun changeState(audioControllerState: ControllerState) {
        controllerState = audioControllerState
        when (audioControllerState) {
            ControllerState.PLAY -> {
                playTracksSimultaneously()
                playSong()
            }
            ControllerState.PLAY_REC -> {
                playTracksSimultaneously()
                recordAudio(trackToRecord)

            }
            ControllerState.REC -> {
                recordAudio(trackToRecord)
            }
            ControllerState.STOP -> {

            }
            ControllerState.PAUSE -> {

            }
            ControllerState.CONTINUE -> {

            }
        }
        audioListener.uiCallback()
    }

    private fun playSong(){
        mainExecutor.execute{
            songToPlay?.second?.playAudio()
        }
    }

    private fun playTracksSimultaneously() {
        mainExecutor.execute{
            val latch = CountDownLatch(trackList.size)
            for (pair in trackList) {
                try {
                    pair.second.playAudio()
                    latch.countDown()
                } catch (e: Exception) {
                    // handle exception
                }
            }
            latch.await()
        }
    }


}


