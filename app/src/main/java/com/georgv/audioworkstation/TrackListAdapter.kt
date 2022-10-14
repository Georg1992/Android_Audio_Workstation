package com.georgv.audioworkstation

import android.content.Context
import android.graphics.Color
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.cardview.widget.CardView
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getColor
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.data.Song
import com.georgv.audioworkstation.data.SongDB
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.database.TrackDao
import com.georgv.audioworkstation.ui.main.MainFragment


class TrackListAdapter(private  val itemClickListener: OnItemClickListener, private val onPlayListener: OnPlayListener) :
    ListAdapter<Track, TrackListAdapter.TrackViewHolder>(
        DiffCallback()
    ) {
    init {
        setHasStableIds(true)
    }

    inner class TrackViewHolder(trackView: View) : RecyclerView.ViewHolder(trackView) {
        val instrumentName: TextView = trackView.findViewById(R.id.instrumentText)
        //val playButton:Button = trackView.findViewById(R.id.playTrackButton)
    }



    override fun onCreateViewHolder(parent: ViewGroup, position: Int): TrackViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.track_view, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val item = getItem(position)
        when(item.isRecording){
            true -> holder.itemView.setBackgroundColor(Color.RED)
            false -> holder.itemView.setBackgroundColor(Color.CYAN)
            null -> return
        }
        holder.instrumentName.text = item.trackName
        //holder.playButton.setOnClickListener {
            //onPlayListener.onPlay()
        //}
    }

    override fun getItemId(position: Int): Long = position.toLong()


    interface OnItemClickListener {
        fun onItemClick(position: Int, trackID: Long)
    }

    interface OnPlayListener {
        fun onPlay()
    }

}

class DiffCallback : DiffUtil.ItemCallback<Track>() {
    override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean {
        return oldItem == newItem
    }

    override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean {
        return oldItem == newItem
    }
}

