package com.georgv.audioworkstation.ui.main

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat.getColor
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.databinding.MainFragmentBinding
import androidx.lifecycle.Observer
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.IOException

class MainFragment : Fragment(),TrackListAdapter.OnItemClickListener,TrackListAdapter.OnPlayListener {

    private val viewModel: SongViewModel by activityViewModels()
    private lateinit var binding: MainFragmentBinding
    private lateinit var recorder: MediaRecorder
    private lateinit var player:MediaPlayer

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = MainFragmentBinding.inflate(inflater, container, false)
        player = MediaPlayer()
        val layoutManager = LinearLayoutManager(context)
        val mRecyclerView = binding.trackListRecyclerView
        mRecyclerView.layoutManager = layoutManager
        setDefaultView()

        val adapter = TrackListAdapter(this,this)
        mRecyclerView.adapter = adapter


        binding.recordButton.setOnClickListener {
            startRecording()
            setRecView()
        }

        binding.stopRecordButton.setOnClickListener{
            stopRecording()
            setDefaultView()
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


    private fun startRecording(){
        recorder = MediaRecorder()
        val dir = "${context?.externalCacheDir?.absolutePath}/"
        val fileName = "track ${viewModel.trackList.value?.count()?.plus(1)}"
        recorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128000)
            setAudioSamplingRate(48000)
            setOutputFile("$dir$fileName.mp3")

            try {
                prepare()
            }catch (e: IOException){}
            start()
        }
        viewModel.recordTrack(fileName,dir)
    }

    private fun stopRecording(){
        recorder.stop()
        recorder.release()
        viewModel.stopRecordTrack()
    }

    private fun setRecView() {
        binding.recordButton.visibility = View.GONE
        binding.stopRecordButton.visibility = View.VISIBLE
    }

    private fun setDefaultView(){
        binding.recordButton.visibility = View.VISIBLE
        binding.stopRecordButton.visibility = View.GONE
    }

    override fun onItemClick(position: Int, trackID: Long) {
        TODO("Not yet implemented")
    }

    override fun onPlay() {

    }


}