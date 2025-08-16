package com.georgv.audioworkstation.ui.main.fragments

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import com.georgv.audioworkstation.audioprocessing.*
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.FragmentEffectBinding
import com.georgv.audioworkstation.ui.main.SongViewModel
import com.google.android.material.slider.Slider


class EffectFragment : Fragment() {


    private lateinit var binding: FragmentEffectBinding
    private lateinit var track: Track
    private val viewModel: SongViewModel by activityViewModels()


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentEffectBinding.inflate(inflater, container, false)

    return binding.root
    }
}