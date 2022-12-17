package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.georgv.audioworkstation.SongListAdapter
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.databinding.LibraryFragmentBinding
import kotlinx.coroutines.launch

class LibraryFragment:Fragment(),SongListAdapter.OnItemClickListener{

    private lateinit var binding: LibraryFragmentBinding
    private val viewModel: SongViewModel by activityViewModels()

    init {

    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = LibraryFragmentBinding.inflate(inflater,container,false)
        binding.plusButton.setOnClickListener {
            lifecycleScope.launch{
                viewModel.createNewSong()
                val song = viewModel.currentSong
                if(song != null){
                    navigateToTheSong(song)
                }
            }
        }
        val layoutManager = LinearLayoutManager(context)
        val songRecyclerView = binding.songsRecyclerView
        songRecyclerView.layoutManager = layoutManager
        val adapter = SongListAdapter(this)
        songRecyclerView.adapter = adapter

        val songListObserver = Observer<List<Song>>{
            adapter.submitList(viewModel.songList.value)
        }
        viewModel.songList.observe(viewLifecycleOwner,songListObserver)

        return binding.root
    }

    private fun navigateToTheSong(song: Song){
        viewModel.updateSongOnNavigate(song)
        val action = LibraryFragmentDirections.actionLibraryFragmentToTrackListFragment(song)
        NavHostFragment.findNavController(this).navigate(action)
    }


    override fun onItemClick(position: Int, song: Song) {
        navigateToTheSong(song)
        Log.d("ITEM CLICKED","FRAGMENT LISTENS")
    }

}