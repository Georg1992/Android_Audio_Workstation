package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import android.util.Log
import android.view.Gravity
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.view.iterator
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.databinding.MainFragmentBinding
import androidx.lifecycle.Observer
import androidx.navigation.findNavController
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.customHandlers.AudioController
import com.georgv.audioworkstation.customHandlers.AudioController.changeState
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import com.google.android.material.snackbar.Snackbar
import android.widget.FrameLayout





class MainFragment : Fragment(), TrackListAdapter.OnItemClickListener {

    private val viewModel: SongViewModel by activityViewModels()
    private lateinit var binding: MainFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        AudioController.fragmentActivitySender = requireActivity()
        binding = MainFragmentBinding.inflate(inflater, container, false)
        ButtonController.binding = this.binding
        val layoutManager = LinearLayoutManager(context)
        val mRecyclerView = binding.trackListRecyclerView
        mRecyclerView.layoutManager = layoutManager
        val adapter = TrackListAdapter()
        mRecyclerView.adapter = adapter
        setEmptySongView()

        binding.playButton.setOnClickListener {
            changeState(AudioController.ControllerState.PLAY)
        }

        binding.recordButton.setOnClickListener {
            if(viewModel.trackList.value?.size!! < 8){
                val fileName = "track${viewModel.trackList.value?.count()?.plus(1)}"
                val pcmdir = "${context?.filesDir?.absolutePath}/$fileName.pcm"
                val wavdir = "${context?.filesDir?.absolutePath}/$fileName.wav"
                viewModel.recordTrack(fileName, pcmdir,wavdir)
                if(AudioController.readyToPlayTrackList.isEmpty()){
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

        binding.stopButton.setOnClickListener {
            changeState(AudioController.ControllerState.STOP)
            viewModel.stopRecordTrack()
        }

        binding.saveSongButton.setOnClickListener { view: View ->
            view.findNavController().navigate(R.id.action_titleFragment_to_libraryFragment)
        }


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
            when(AudioController.readyToPlayTrackList.isNotEmpty()){
                true -> binding.playButton.visibility = View.VISIBLE
                false -> binding.playButton.visibility = View.GONE
            }
        }


        private fun setAllButtonsInvisible() {
            for (button: View in binding.buttonView) {
                if (button is ImageButton) {
                    button.visibility = View.GONE
                }
            }
        }
    }


    override fun onItemClick(position: Int, trackID: Long) {
        TODO("Not yet implemented")
    }


}

