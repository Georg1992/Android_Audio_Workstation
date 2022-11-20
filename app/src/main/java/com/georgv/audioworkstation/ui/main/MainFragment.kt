package com.georgv.audioworkstation.ui.main

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.app.Dialog
import android.content.DialogInterface
import android.graphics.Color
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
import androidx.recyclerview.widget.LinearLayoutManager
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.databinding.MainFragmentBinding
import androidx.lifecycle.Observer
import com.georgv.audioworkstation.customHandlers.AudioController
import com.georgv.audioworkstation.customHandlers.AudioController.changeState
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import com.google.android.material.snackbar.Snackbar
import android.widget.FrameLayout
import androidx.fragment.app.DialogFragment
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.UiListener
import kotlinx.android.synthetic.main.main_fragment.*
import kotlinx.android.synthetic.main.main_fragment.view.*
import java.lang.IllegalStateException


class MainFragment : Fragment(), TrackListAdapter.OnItemClickListener,View.OnClickListener {

    private val viewModel: SongViewModel by activityViewModels()
    private lateinit var binding: MainFragmentBinding
    private lateinit var uiListener: UiListener
    private lateinit var mRecyclerView:RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        AudioController.fragmentActivitySender = requireActivity()
        binding = MainFragmentBinding.inflate(inflater, container, false)
        ButtonController.binding = this.binding
        val layoutManager = LinearLayoutManager(context)
        mRecyclerView = binding.trackListRecyclerView
        mRecyclerView.layoutManager = layoutManager
        val adapter = TrackListAdapter(this)
        mRecyclerView.adapter = adapter
        uiListener = adapter
        setEmptySongView()

        binding.playButton.setOnClickListener(this)
        binding.recordButton.setOnClickListener (this)
        binding.stopButton.setOnClickListener(this)
        binding.pauseButton.setOnClickListener(this)
        binding.saveSongButton.setOnClickListener(this)

        val trackListObserver = Observer<List<Track>> {
            adapter.submitList(viewModel.trackList.value)
        }
        viewModel.trackList.observe(viewLifecycleOwner, trackListObserver)

        val songlistObserver = Observer<List<Song>> {

        }
        viewModel.songList.observe(viewLifecycleOwner, songlistObserver)


        return binding.root
    }




    companion object ButtonController {
        private lateinit var binding: MainFragmentBinding

        fun setEmptySongView(){
            setAllButtonsInvisible()
            binding.recordButton.visibility = View.VISIBLE
        }

        fun setStopView(){
            setAllButtonsInvisible()
            setPlayButton()
            binding.recordButton.visibility = View.VISIBLE
            binding.saveSongButton.visibility = View.VISIBLE
        }

        fun setPauseView(){
            setAllButtonsInvisible()
            setPlayButton()
            binding.buttonView.playButton.blink()
        }

        fun setPlayView(){
            setAllButtonsInvisible()
            binding.stopButton.visibility = View.VISIBLE
            binding.pauseButton.visibility = View.VISIBLE
        }

        fun setRecView(){
            setAllButtonsInvisible()
            binding.stopButton.visibility = View.VISIBLE
        }

        fun setPlayButton(){
            when(AudioController.playerList.isNotEmpty()){
                true -> binding.playButton.visibility = View.VISIBLE
                false -> binding.playButton.visibility = View.GONE
            }
        }

        private fun setAllButtonsInvisible() {
            for (button:View in binding.buttonView) {
                if (button is ImageButton) {
                    button.visibility = View.GONE
                }
            }
        }

        fun View.blink(
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

        fun View.colorBlink(colorFrom:Int, colorTo:Int, view: View) {
            val colorAnimation = ValueAnimator.ofObject(ArgbEvaluator(), colorFrom, colorTo)
            colorAnimation.duration = 400 // milliseconds
            colorAnimation.addUpdateListener { animator -> view.setBackgroundColor(animator.animatedValue as Int) }
            colorAnimation.start()
        }


    }

    override fun onItemClick(position: Int, trackID: Long) {
        TODO("Not yet implemented")
    }



    override fun onClick(p0: View?) {
        when(p0){
            binding.playButton -> {
                if(AudioController.controllerState == AudioController.ControllerState.PAUSE){
                    changeState(AudioController.ControllerState.CONTINUE)
                    binding.playButton.clearAnimation()
                }else{
                    changeState(AudioController.ControllerState.PLAY)
                }
            }

            binding.stopButton -> {
                changeState(AudioController.ControllerState.STOP)
                viewModel.stopRecordTrack()
            }

            binding.recordButton ->{
                if(viewModel.trackList.value?.size!! < 8){
                    val fileName = "track${viewModel.trackList.value?.count()?.plus(1)}"
                    val pcmdir = "${context?.filesDir?.absolutePath}/$fileName.pcm"
                    val wavdir = "${context?.filesDir?.absolutePath}/$fileName.wav"
                    viewModel.recordTrack(fileName, pcmdir,wavdir)
                    if(AudioController.playerList.isEmpty()){
                        changeState(AudioController.ControllerState.REC)
                    }else{
                        changeState(AudioController.ControllerState.PLAYREC)
                    }
                }else{
                    val snack = Snackbar.make(
                        mRecyclerView,
                        "TOO MANY TRACKS",
                        Snackbar.LENGTH_SHORT
                    )
                    val view = snack.view
                    val params = view.layoutParams as FrameLayout.LayoutParams
                    params.gravity = Gravity.CENTER
                    view.setBackgroundResource(R.color.redTransparent)
                    snack.show()
                }
            }

            binding.pauseButton -> {
                changeState(AudioController.ControllerState.PAUSE)
            }

            binding.saveSongButton -> {
                findNavController().navigate(R.id.action_titleFragment_to_libraryFragment)
            }


        }
        uiListener.uiCallback()
    }


}

