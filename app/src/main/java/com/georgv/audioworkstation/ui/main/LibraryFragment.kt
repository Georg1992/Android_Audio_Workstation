package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.OnPlayListener
import com.georgv.audioworkstation.TrackListAdapter
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.LibraryFragmentBinding
import com.georgv.audioworkstation.databinding.MainFragmentBinding

class LibraryFragment:Fragment(),TrackListAdapter.OnItemClickListener{

    private lateinit var binding: LibraryFragmentBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = LibraryFragmentBinding.inflate(inflater,container,false)
        val songRecyclerView = binding.songsRecyclerView
        songRecyclerView.adapter = TrackListAdapter(this)

        return binding.root
    }

    override fun onItemClick(position: Int, trackID: Long) {
        TODO("Not yet implemented")
    }

}