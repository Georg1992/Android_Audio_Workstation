package com.georgv.audioworkstation.ui.main

import android.app.Notification
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
        arguments?.let {

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {

        binding = FragmentLoudSpeakerBinding.inflate(inflater,container,false)
        binding.loudspeakerSwitch.setOnClickListener{
            if(!isStreaming){
                AudioController.changeState(AudioController.ControllerState.STREAM)
            }else{
                AudioController.changeState(AudioController.ControllerState.STOP)
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
        val randomColor = Color.rgb(
            Random.nextInt(256),  // Red component (0-255)
            Random.nextInt(256),  // Green component (0-255)
            Random.nextInt(256)   // Blue component (0-255)
        )
        binding.loudspeakerSwitch.setBackgroundColor(randomColor)
    }
}

interface Streamer{
    fun startAudioStreaming()
    fun stopAudioStreaming()
}