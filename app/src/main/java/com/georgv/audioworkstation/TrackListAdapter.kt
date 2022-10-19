package com.georgv.audioworkstation
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.customHandlers.AudioController
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.ui.main.MainFragment


class TrackListAdapter() : ListAdapter<Track, TrackListAdapter.TrackViewHolder>(DiffCallback()) {



    init {
        setHasStableIds(true)
    }


    inner class TrackViewHolder(trackView: View) : RecyclerView.ViewHolder(trackView),
        View.OnClickListener {
        init {
            itemView.setOnClickListener(this)
        }

        var selected: Boolean = false
        val instrumentName: TextView = trackView.findViewById(R.id.instrumentText)
        override fun onClick(p0: View?) {
            if(AudioController.controllerState == AudioController.ControllerState.STOP){
                selected = !selected
                onBindViewHolder(this, adapterPosition)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): TrackViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.track_view, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val item = getItem(position)
        holder.instrumentName.text = item.trackName

        if (item.isRecording == true) {
            holder.itemView.setBackgroundResource(R.color.redTransparent)
            holder.selected = false
            return
        }
        when (holder.selected) {
            true -> {
                holder.itemView.setBackgroundResource(R.color.green)
                AudioController.readyToPlayTrackList.add(item)
            }
            false -> {
                holder.itemView.setBackgroundResource(R.color.blue)
                AudioController.readyToPlayTrackList.remove(item)
            }
        }
        MainFragment.setPlayButton()
    }


    override fun getItemId(position: Int): Long = position.toLong()


    interface OnItemClickListener {
        fun onItemClick(position: Int, trackID: Long)
    }


    private class DiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem == newItem
        }

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem == newItem
        }
    }


}

interface OnPlayListener {
    fun getTrackData(): String
}






