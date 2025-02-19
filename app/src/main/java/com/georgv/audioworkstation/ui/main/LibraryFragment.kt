package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.SongListAdapter
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.databinding.LibraryFragmentBinding
import com.georgv.audioworkstation.ui.main.dialogs.CreateSongDialogFragment
import kotlinx.coroutines.launch

class LibraryFragment:Fragment(),SongListAdapter.OnSongItemClickListener, DialogCaller{

    private lateinit var binding: LibraryFragmentBinding
    private val viewModel: SongViewModel by activityViewModels()
    private lateinit var songRecyclerView:RecyclerView

    init {
        AudioController.trackList.clear()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = LibraryFragmentBinding.inflate(inflater,container,false)
        binding.plusButton.setOnClickListener {
            it.isEnabled = false
            val manager = parentFragmentManager
            val dialog = CreateSongDialogFragment(this)
            dialog.show(manager,"CREATE NEW SONG")
            it.isEnabled = true
        }

        val layoutManager = LinearLayoutManager(context)
        songRecyclerView = binding.songsRecyclerView
        songRecyclerView.layoutManager = layoutManager
        val adapter = SongListAdapter(this)
        songRecyclerView.adapter = adapter

        lifecycleScope.launch {
//            viewModel.songList.collect { songs ->
//                adapter.submitList(songs)
//            }
        }

        return binding.root
    }


    private fun navigateToTheSong(song: Song){
        viewModel.updateSongOnNavigate(song)
        val action = LibraryFragmentDirections.actionLibraryFragmentToTrackListFragment()
        NavHostFragment.findNavController(this).navigate(action)
    }


    override fun onItemClick(position: Int, song: Song) {
        navigateToTheSong(song)
    }

    override fun onDeleteClick(songID:String) {
        viewModel.deleteSongFromDB(songID)
    }



    override fun delegateFunctionToDialog(songName: String) {

    }


}

interface DialogCaller{
    fun delegateFunctionToDialog(songName:String)
}

