package com.georgv.audioworkstation.ui.main

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.support.v4.media.RatingCompat.Style
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.OnBackPressedCallback
import androidx.activity.OnBackPressedDispatcher
import androidx.core.content.ContextCompat
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.audioprocessing.AudioStreamingService
import com.georgv.audioworkstation.databinding.FragmentLoudSpeakerBinding
import kotlin.random.Random

class LoudSpeakerFragment : Fragment(), AudioListener, Streamer {

    init {
        AudioController.audioListener = this
        AudioController.streamer = this
    }

    private lateinit var binding: FragmentLoudSpeakerBinding
    private var isStreaming = false


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = FragmentLoudSpeakerBinding.inflate(inflater,container,false)
        binding.loudspeakerSwitch.setOnClickListener{
            if(!isStreaming){
                AudioController.changeState(AudioController.ControllerState.STREAM)
                binding.loudspeakerSwitch.setBackgroundResource(R.color.darkRed)
            }else{
                AudioController.changeState(AudioController.ControllerState.STOP)
                binding.loudspeakerSwitch.setBackgroundResource(R.color.yellow)
            }
            isStreaming=!isStreaming
        }

        return binding.root
    }

    override fun startAudioStreaming(){
        val intent = Intent(context,AudioStreamingService::class.java)
        context?.startService(intent)
    }

    override fun stopAudioStreaming(){
        val intent = Intent(context,AudioStreamingService::class.java)
        context?.stopService(intent)
    }

    override fun uiCallback() {

    }

    override fun onDestroy() {
        stopAudioStreaming()
        super.onDestroy()
    }
}

interface Streamer{
    fun startAudioStreaming()
    fun stopAudioStreaming()
}