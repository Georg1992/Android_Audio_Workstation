package com.georgv.audioworkstation.ui.main

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Bundle
import android.view.Gravity
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageButton
import androidx.core.view.iterator
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.georgv.audioworkstation.TrackListAdapter
import androidx.lifecycle.Observer
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.audioprocessing.AudioController.changeState
import com.georgv.audioworkstation.data.Track
import com.google.android.material.snackbar.Snackbar
import android.widget.FrameLayout
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.audioprocessing.AudioController.controllerState
import com.georgv.audioworkstation.audioprocessing.AudioProcessingCallback
import com.georgv.audioworkstation.audioprocessing.AudioProcessor
import com.georgv.audioworkstation.databinding.TrackListFragmentBinding

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths


class TrackListFragment : Fragment(), View.OnClickListener, AudioListener, AudioProcessingCallback {

    private val viewModel: SongViewModel by activityViewModels()
    private lateinit var binding: TrackListFragmentBinding
    private lateinit var mRecyclerView: RecyclerView


    init {
        AudioController.trackList.clear()
        AudioController.audioListener = this
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        AudioController.fragmentActivitySender = requireActivity()
        binding = TrackListFragmentBinding.inflate(inflater, container, false)
        binding.progressBar.visibility = View.GONE
        binding.processingText.visibility = View.GONE
        val layoutManager = LinearLayoutManager(context)
        mRecyclerView = binding.trackListRecyclerView
        mRecyclerView.layoutManager = layoutManager
        val adapter = TrackListAdapter(this)
        mRecyclerView.adapter = adapter


        //binding.songName.text = args.selectedSong.songName
        binding.playButton.setOnClickListener(this)
        binding.recordButton.setOnClickListener(this)
        binding.stopButton.setOnClickListener(this)
        binding.pauseButton.setOnClickListener(this)
        binding.saveSongButton.setOnClickListener(this)

        val trackListObserver = Observer<List<Track>> {
            adapter.submitList(viewModel.trackList.value)
        }
        viewModel.trackList.observe(viewLifecycleOwner, trackListObserver)

        return binding.root
    }


    override fun onClick(p0: View?) {
        when (p0) {
            binding.playButton -> {
                if (controllerState == AudioController.ControllerState.PAUSE) {
                    changeState(AudioController.ControllerState.CONTINUE)
                } else {
                    changeState(AudioController.ControllerState.PLAY)
                }
            }

            binding.stopButton -> {
                changeState(AudioController.ControllerState.STOP)
                viewModel.stopRecordTrack()
            }

            binding.recordButton -> {
                viewModel.createTrack(requireContext())
                if (AudioController.trackList.isEmpty()) {
                    changeState(AudioController.ControllerState.REC)
                } else {
                    changeState(AudioController.ControllerState.PLAY_REC)
                }
            }


            binding.pauseButton -> {
                changeState(AudioController.ControllerState.PAUSE)
            }

            binding.saveSongButton -> {
                val processor = AudioProcessor()
                processor.setSongToProcessor(viewModel.currentSong)
                val trackList = viewModel.trackList.value
                val songWavFileDir = viewModel.currentSong.wavFilePath
                if ((trackList != null)
                    && trackList.isNotEmpty()
                    && (songWavFileDir != null)
                    && controllerState == AudioController.ControllerState.STOP
                ) {
                    val wavFile = File(songWavFileDir)

                    processor.mixAudio(trackList,wavFile,this)
                } else {
                    showEmptySongSnackBar()
                }
            }
        }
        setButtonUI()
    }

    fun deleteTrack(trackId: Long, wavFilePath: String) {
        viewModel.deleteTrackFromDb(trackId)
        try {
            Files.delete(Paths.get(wavFilePath))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun updateTrackVolume(volume:Float,id:Long){
        viewModel.updateTrackVolumeToDb(volume,id)
    }

    fun setButtonUI(){
        when(controllerState){
            AudioController.ControllerState.STOP -> {
                if(AudioController.trackList.isEmpty()){
                    setDefaultView()
                }else{
                    setReadyToPlayUI()
                }
            }
            AudioController.ControllerState.PLAY -> setPlayingUI()
            AudioController.ControllerState.CONTINUE -> setPlayingUI()
            AudioController.ControllerState.REC -> setRecordingUI()
            AudioController.ControllerState.PLAY_REC -> setRecordingUI()
            AudioController.ControllerState.PAUSE -> setPauseUI()
            else -> {}
        }
    }

    private fun setAllButtonsInvisible(){
        for (button:View in binding.buttonLayout) {
            if (button is ImageButton) {
                button.visibility = View.INVISIBLE
            }
        }
    }

    private fun setDefaultView(){
        setAllButtonsInvisible()
        binding.recordButton.visibility = View.VISIBLE
    }

    private fun setReadyToPlayUI(){
        setAllButtonsInvisible()
        binding.playButton.visibility = View.VISIBLE
        binding.recordButton.visibility = View.VISIBLE

    }

    private fun setPlayingUI(){
        setAllButtonsInvisible()
        binding.stopButton.visibility = View.VISIBLE
        binding.pauseButton.visibility = View.VISIBLE
    }

    private fun setRecordingUI(){
        setAllButtonsInvisible()
        binding.stopButton.visibility = View.VISIBLE
    }

    private fun setPauseUI(){
        setAllButtonsInvisible()
        binding.pauseButton.visibility = View.VISIBLE
        binding.playButton.visibility = View.VISIBLE
        binding.stopButton.visibility = View.VISIBLE
    }




    private fun View.blink(
        times: Int = Animation.INFINITE,
        duration: Long = 1L,
        offset: Long = 400L,
        minAlpha: Float = 0.0f,
        maxAlpha: Float = 1.0f,
        repeatMode: Int = Animation.REVERSE
    ) {
        startAnimation(AlphaAnimation(minAlpha, maxAlpha).also {
            it.duration = duration
            it.startOffset = offset
            it.repeatMode = repeatMode
            it.repeatCount = times
        })
    }

    private fun colorBlink(colorFrom: Int, colorTo: Int, view: View) {
        val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
        colorAnimation.duration = 400 // milliseconds
        colorAnimation.addUpdateListener { animator -> view.setBackgroundColor(animator.animatedValue as Int) }
        colorAnimation.start()
    }

    private fun showTooManyTracksSnackBar() {
        val snack = Snackbar.make(
            mRecyclerView,
            "TOO MANY TRACKS",
            Snackbar.LENGTH_SHORT
        )
        val view = snack.view
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.CENTER
        view.setBackgroundResource(R.color.bright_green)
        snack.show()
    }

    private fun showEmptySongSnackBar() {
        val snack = Snackbar.make(
            mRecyclerView,
            "RECORD SOMETHING BEFORE SAVING",
            Snackbar.LENGTH_SHORT
        )
        val view = snack.view
        val params = view.layoutParams as FrameLayout.LayoutParams
        params.gravity = Gravity.CENTER
        view.setBackgroundResource(R.color.bright_green)
        snack.show()
    }

    override fun uiCallback() {
        setButtonUI()
    }

    override fun onProcessingStarted() {
        binding.trackListRecyclerView.visibility = View.GONE
        binding.progressBar.visibility = View.VISIBLE
        binding.processingText.visibility = View.VISIBLE
    }


    override fun onProcessingProgress(progress: String) {
        val str = "PROCESSING TRACK: $progress"
        binding.processingText.text = str
    }

    override fun onProcessingFinished() {
        findNavController().navigate(R.id.action_titleFragment_to_libraryFragment)
    }



}

interface AudioListener{
    fun uiCallback()

}