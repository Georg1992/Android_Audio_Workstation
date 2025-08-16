package com.georgv.audioworkstation.ui.main.fragments

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.os.Bundle
import android.util.Log
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
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.google.android.material.snackbar.Snackbar
import android.widget.FrameLayout
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.databinding.SongFragmentBinding
import com.georgv.audioworkstation.ui.main.SongViewModel


// Removed unused imports


class SongFragment : Fragment(), View.OnClickListener {

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
            if (song != null) {
                Log.i("SongFragment", "Song updated: ${song.name} (ID: ${song.id})")
                binding.songName.text = song.name
                // Load tracks for this song
                viewModel.loadTracksForCurrentSong()
            } else {
                Log.w("SongFragment", "Song is null")
                binding.songName.text = "No Song Selected"
            }
        }
        
        // Observe tracks StateFlow and update adapter
        lifecycleScope.launchWhenStarted {
            viewModel.tracks.collectLatest { tracks ->
                Log.i("SongFragment", "Tracks updated: ${tracks.size} tracks")
                tracks.forEach { track ->
                    Log.i("SongFragment", "Track in list: ${track.name} - Recording: ${track.isRecording}")
                }
                // Create a new list to ensure adapter gets updated
                val adapter = mRecyclerView.adapter as? TrackListAdapter
                adapter?.submitList(tracks.toList()) // Force a new list instance
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
    
    // Track selection methods for adapter
    fun toggleTrackSelection(trackId: String) {
        viewModel.toggleTrackSelection(trackId)
        // Refresh the specific item in the adapter
        val adapter = mRecyclerView.adapter as? TrackListAdapter
        val tracks = viewModel.tracks.value
        val position = tracks.indexOfFirst { it.id == trackId }
        if (position != -1) {
            adapter?.notifyItemChanged(position)
        }
    }
    
    fun isTrackSelected(trackId: String): Boolean {
        return viewModel.isTrackSelected(trackId)
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

}

interface AudioListener{
    fun uiCallback()
}