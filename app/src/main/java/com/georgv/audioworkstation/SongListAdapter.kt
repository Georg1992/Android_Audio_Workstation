package com.georgv.audioworkstation

import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.databinding.SongHolderViewBinding
import com.georgv.audioworkstation.ui.main.AudioListener

class SongListAdapter(val listener:OnItemClickListener): ListAdapter<Song, SongListAdapter.SongViewHolder>(DiffCallback()) {
    private lateinit var binding: SongHolderViewBinding

    inner class SongViewHolder(itemBinding:SongHolderViewBinding):RecyclerView.ViewHolder(itemBinding.root), View.OnClickListener, AudioListener{
        lateinit var song:Song


        init {
            AudioController.audioListener = this
            this.itemView.setOnClickListener(this)
            binding.deleteButton.setOnClickListener {
                listener.onDeleteClick(song.id)
            }

            binding.playSongButton.setOnClickListener{
                //AudioController.createAudioTrack()


                AudioController.changeState(AudioController.ControllerState.PLAY)
            }

            binding.stopSongButton.setOnClickListener{
                AudioController.changeState(AudioController.ControllerState.STOP)
            }
        }

        override fun onClick(p0: View?) {
            val position = adapterPosition
            if (position != RecyclerView.NO_POSITION) {
                listener.onItemClick(position, song)
            }
        }

        private fun setUI(){
            when(AudioController.controllerState){
                AudioController.ControllerState.STOP -> setStoppedUI()
                AudioController.ControllerState.PLAY -> setPlayingUI()
                else -> setStoppedUI()
            }
        }

        private fun setStoppedUI(){
            binding.stopSongButton.visibility = View.INVISIBLE
            binding.playSongButton.visibility = View.VISIBLE
        }

        private fun setPlayingUI(){
            binding.stopSongButton.visibility = View.VISIBLE
            binding.playSongButton.visibility = View.INVISIBLE
        }

        override fun uiCallback() {
            setUI()
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup,position: Int): SongViewHolder {
        binding = SongHolderViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return SongViewHolder(binding)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        holder.song = getItem(position)
        binding.songName.text = holder.song.songName
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
        fun onDeleteClick(songID:Long)
    }
}

