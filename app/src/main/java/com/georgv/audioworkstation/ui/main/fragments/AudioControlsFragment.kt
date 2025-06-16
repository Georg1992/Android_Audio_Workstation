package com.georgv.audioworkstation.ui.main.fragments

import android.content.Context
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.databinding.FragmentAudioControlsBinding
import com.georgv.audioworkstation.ui.main.SongViewModel

class AudioControlsFragment:Fragment() {
    private lateinit var binding:FragmentAudioControlsBinding
    private val viewModel: SongViewModel by activityViewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAudioControlsBinding.inflate(inflater,container,false)

        val clickAnimation = AnimationUtils.loadAnimation(context, R.anim.button_click)
        showPlay()

        binding.playButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            showPause()
        }

        binding.pauseButton.setOnClickListener{
            it.startAnimation(clickAnimation)
            showPlayPause()
        }

        binding.playPauseButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            showPause()
        }

        binding.stopButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            showPlay()
        }

        binding.recordButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            showPause()
        }

        return binding.root
    }

    private fun showPlay() {
        binding.playPauseButton.visibility = View.GONE
        binding.pauseButton.visibility = View.GONE
        binding.playButton.visibility = View.VISIBLE
    }

    private fun showPlayPause() {
        binding.playButton.visibility = View.GONE
        binding.pauseButton.visibility = View.GONE
        binding.playPauseButton.visibility = View.VISIBLE
    }

    private fun showPause() {
        binding.playButton.visibility = View.GONE
        binding.playPauseButton.visibility = View.GONE
        binding.pauseButton.visibility = View.VISIBLE
    }

}