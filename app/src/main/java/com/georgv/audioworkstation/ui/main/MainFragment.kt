package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.MainFragmentBinding
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.RecyclerView

class MainFragment : Fragment() {

    private val viewModel: TrackListViewModel by activityViewModels()
    private  lateinit var binding: MainFragmentBinding
    private lateinit var adapter: TrackListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {


        binding = MainFragmentBinding.inflate(layoutInflater,container,false)
        val layoutManager = LinearLayoutManager(context)
        val mRecyclerView:RecyclerView = binding.trackListRecyclerView
        mRecyclerView.layoutManager = layoutManager

        adapter = TrackListAdapter(viewModel.createNewTrackList())
        mRecyclerView.adapter = adapter



        binding.recordButton.setOnClickListener {
            viewModel.addNewTrack(adapter.trackList)

        }
        val listObserver = Observer<List<Track>> { newTracks ->
            adapter.notifyDataSetChanged()
            Log.d("VIEWMODEL","DATA CHANGED:${viewModel.currentTracks.value}")
            Log.d("OBSERVER","DATA CHANGED: ${adapter.trackList}")
        }
        viewModel.currentTracks.observe(viewLifecycleOwner, listObserver)




        return binding.root
    }





}