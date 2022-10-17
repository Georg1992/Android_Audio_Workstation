package com.georgv.audioworkstation

import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track

class SongListAdapter(): ListAdapter<Song, SongListAdapter.SongViewHolder>(DiffCallback()) {

    inner class SongViewHolder(songView:View):RecyclerView.ViewHolder(songView){

    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        TODO("Not yet implemented")
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        TODO("Not yet implemented")
    }


    private class DiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }
}

