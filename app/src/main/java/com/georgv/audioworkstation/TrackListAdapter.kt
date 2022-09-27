package com.georgv.audioworkstation

import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.data.Track



class TrackListAdapter(val trackList: ArrayList<Track>) :
    RecyclerView.Adapter<TrackListAdapter.TrackViewHolder>() {


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrackViewHolder {
        Log.d("DEBUG", "Initializing Viewholder")
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.track_view, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        Log.d("DEBUG", "Binding ViewHolder")
        val currentTrack = trackList.get(position)
        holder.instrumentName.text = currentTrack.instrument
    }

    override fun getItemCount(): Int {
        Log.d("getItemCount", "${trackList.size}")
        return trackList.size
    }


    inner class TrackViewHolder(trackView: View) : RecyclerView.ViewHolder(trackView) {
        val instrumentName: TextView = trackView.findViewById(R.id.instrument)

    }

}

