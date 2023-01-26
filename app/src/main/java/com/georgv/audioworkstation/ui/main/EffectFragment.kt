package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.Switch
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.fragment.navArgs
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.databinding.FragmentEffectBinding


class EffectFragment : Fragment() {


    private lateinit var binding:FragmentEffectBinding
    private val viewModel: SongViewModel by activityViewModels()
    private val args:EffectFragmentArgs by navArgs()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentEffectBinding.inflate(inflater,container,false)
        val track = args.selectedTrack
        val reverbSwitch:Switch = binding.switchReverb
        val applyAllButton:ImageButton = binding.applyAllEffect
        val trackName = binding.trackName
        trackName.text = track.trackName

        applyAllButton.setOnClickListener{
            val song = viewModel.currentSong
            if(song != null){
                val action = EffectFragmentDirections.actionEffectFragmentToTrackListFragment(song)
                NavHostFragment.findNavController(this).navigate(action)
            }
        }
        // Inflate the layout for this fragment
        return binding.root
    }



}