package com.georgv.audioworkstation.ui.main
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isNotEmpty
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.databinding.MainFragmentBinding
import androidx.lifecycle.Observer
import androidx.navigation.findNavController
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.customHandlers.AudioController
import com.georgv.audioworkstation.customHandlers.AudioController.isRecordingAudio
import com.georgv.audioworkstation.customHandlers.AudioController.playTracks
import com.georgv.audioworkstation.customHandlers.AudioController.startRecording
import com.georgv.audioworkstation.customHandlers.AudioController.stopPlay
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track


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
        val layoutManager = LinearLayoutManager(context)
        val mRecyclerView = binding.trackListRecyclerView
        mRecyclerView.layoutManager = layoutManager
        setDefaultView()

        val adapter = TrackListAdapter(this)
        mRecyclerView.adapter = adapter

        binding.playButton.setOnClickListener {
            if(mRecyclerView.isNotEmpty()){
                playTracks(mRecyclerView)
                setBusyView()
            }
        }

        binding.recordButton.setOnClickListener {
            val fileName = "track${viewModel.trackList.value?.count()?.plus(1)}"
            val dir = "${context?.filesDir?.absolutePath}/$fileName.wav"
            isRecordingAudio = true
            viewModel.recordTrack(fileName, dir)
            startRecording(dir)
            setBusyView()
            playTracks(mRecyclerView)
        }

        binding.stopButton.setOnClickListener {
            isRecordingAudio = false
            viewModel.stopRecordTrack()
            stopPlay()
            setDefaultView()
            AudioController.recordingThread = null
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


    private fun setBusyView() {
        binding.recordButton.visibility = View.GONE
        binding.stopButton.visibility = View.VISIBLE
        binding.playButton.visibility = View.GONE
    }

    private fun setDefaultView() {
        binding.recordButton.visibility = View.VISIBLE
        binding.stopButton.visibility = View.GONE
        binding.playButton.visibility = View.VISIBLE
    }

    override fun onItemClick(position: Int, trackID: Long) {
        TODO("Not yet implemented")
    }
}