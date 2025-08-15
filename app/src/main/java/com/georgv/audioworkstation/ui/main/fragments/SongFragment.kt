package com.georgv.audioworkstation.ui.main.fragments

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
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.audioprocessing.AudioController.changeState
import com.google.android.material.snackbar.Snackbar
import android.widget.FrameLayout
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.audioprocessing.AudioController.controllerState
import com.georgv.audioworkstation.audioprocessing.AudioProcessingCallback
import com.georgv.audioworkstation.databinding.SongFragmentBinding
import com.georgv.audioworkstation.ui.main.SongViewModel


import java.io.IOException
import java.nio.file.Files
import java.nio.file.Paths


class SongFragment : Fragment(), View.OnClickListener, AudioListener, AudioProcessingCallback {

    private val viewModel: SongViewModel by activityViewModels()
    private lateinit var binding: SongFragmentBinding
    private lateinit var mRecyclerView: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        AudioController.fragmentActivitySender = requireActivity()
        binding = SongFragmentBinding.inflate(inflater, container, false)
        binding.progressBar.visibility = View.GONE
        binding.processingText.visibility = View.GONE
        val layoutManager = LinearLayoutManager(context)
        mRecyclerView = binding.trackListRecyclerView
        mRecyclerView.layoutManager = layoutManager
        val adapter = TrackListAdapter(this)
        mRecyclerView.adapter = adapter

        binding.saveSongButton.setOnClickListener(this)

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            song?.let {
                binding.songName.text = it.name
            }
        }



        return binding.root
    }


    override fun onClick(p0: View?) {

    }

    fun deleteTrack(trackId: String, wavFilePath: String) {
        viewModel.deleteTrackFromDb(trackId)
        try {
            Files.delete(Paths.get(wavFilePath))
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }

    fun updateTrackVolume(volume:Float,id:String){
        viewModel.updateTrackVolumeToDb(volume,id)
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

    override fun uiCallback() {
        // no-op for now
    }


}

interface AudioListener{
    fun uiCallback()

}