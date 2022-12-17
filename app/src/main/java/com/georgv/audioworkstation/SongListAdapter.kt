package com.georgv.audioworkstation

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.SongHolderViewBinding

class SongListAdapter(val listener:OnItemClickListener): ListAdapter<Song, SongListAdapter.SongViewHolder>(DiffCallback()) {


    inner class SongViewHolder(itemBinding:SongHolderViewBinding):RecyclerView.ViewHolder(itemBinding.root), View.OnClickListener{

        init {
            this.itemView.setOnClickListener(this)
        }

        override fun onClick(p0: View?) {
            val position = adapterPosition
            val item = getItem(position)
            if (position != RecyclerView.NO_POSITION) {
                listener.onItemClick(position, item)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup,position: Int): SongViewHolder {
        val binding = SongHolderViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.deleteButton.setOnClickListener {

        }
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {

    }


    private class DiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean {
            return oldItem == newItem
        }
    }

    interface OnItemClickListener {
        fun onItemClick(position: Int, song: Song)
    }
}

