package com.georgv.audioworkstation

import android.media.MediaPlayer
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment.Companion.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.audioprocessing.Reverb
import com.georgv.audioworkstation.customHandlers.CustomDataSource
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.TrackHolderViewBinding
import com.georgv.audioworkstation.ui.main.TrackListFragment
import com.georgv.audioworkstation.ui.main.TrackListFragmentDirections
import com.google.android.material.slider.Slider


private const val DEBUG_TAG = "Gestures"


class TrackListAdapter(val parentFragment: TrackListFragment) : ListAdapter<Track, TrackListAdapter.TrackViewHolder>(DiffCallback()),
    UiListener {

    private val viewHolders: MutableList<TrackViewHolder> = mutableListOf()

    init {
        Log.d("I INIT ADAPTER", "I INIT ADAPTER")
        hideAllSliders()
    }

    inner class TrackViewHolder(binding: TrackHolderViewBinding) : RecyclerView.ViewHolder(binding.root),
        View.OnClickListener{
        lateinit var track: Track
        var trackId:Long = 0
        var player: MediaPlayer? = null

        var selected: Boolean = false
        val instrumentName: TextView = binding.instrumentText
        val volumeSlider = binding.slider
        var wavPath:String = ""
        var pcmPath:String = ""


        init {

            Log.d("I INIT", "I INIT")

            itemView.setOnClickListener(this)
            binding.effectsButton.setOnClickListener {
                if(AudioController.controllerState == AudioController.ControllerState.STOP){
                    val action = TrackListFragmentDirections.actionTitleFragmentToEffectFragment(track)
                    findNavController(parentFragment).navigate(action)
                }

            }

            binding.deleteButton.setOnClickListener {
                if(AudioController.controllerState == AudioController.ControllerState.STOP){
                    selected = false
                    releasePlayer(this)
                    parentFragment.deleteTrack(trackId, pcmPath, wavPath)
                    uiCallback()
                }
            }

            binding.volumeButton.setOnClickListener{
                if(volumeSlider.isVisible){
                    hideAllSliders()
                }else{
                    hideAllSliders()
                    volumeSlider.visibility = View.VISIBLE
                }
            }

            volumeSlider.stepSize = 1f
            volumeSlider.addOnChangeListener{ slider: Slider, fl: Float, _: Boolean ->
                if(player != null){
                    AudioController.controlVolume(player, fl)
                }

            }

            volumeSlider.addOnSliderTouchListener(object:Slider.OnSliderTouchListener{
                override fun onStartTrackingTouch(slider: Slider) {

                }
                override fun onStopTrackingTouch(slider: Slider) {
                    parentFragment.updateTrackVolume(slider.value, trackId)
                }
            })

        }


        override fun onClick(p0: View?) {
            if (AudioController.controllerState == AudioController.ControllerState.STOP) {
                selected = !selected
                onBindViewHolder(this, adapterPosition)
            }
        }


    }

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): TrackViewHolder {
        val itemBinding = TrackHolderViewBinding.inflate(LayoutInflater.from(parent.context),parent,false)
        return TrackViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val item = getItem(position)
        holder.track = item
        holder.trackId = item.id
        holder.instrumentName.text = item.trackName
        holder.volumeSlider.value = item.volume
        holder.wavPath = item.wavDir
        viewHolders.add(holder)
        if (item.isRecording == true) {
            holder.itemView.setBackgroundResource(R.color.redTransparent)
            holder.selected = false
            return
        }
        when (holder.selected) {
            true -> {
                holder.itemView.setBackgroundResource(R.color.green)
                val newPlayer = MediaPlayer()
                newPlayer.apply {
                    setDataSource(CustomDataSource(item))
                    //setDataSource(item.wavDir)
                }
                holder.player = newPlayer
                AudioController.playerList.add(newPlayer)
            }
            false -> {
                releasePlayer(holder)
                holder.itemView.setBackgroundResource(R.color.blue)
            }
        }
        uiCallback()
    }

    private fun releasePlayer(holder: TrackViewHolder){
        if (holder.player != null) {
            holder.player?.release()
            AudioController.playerList.remove(holder.player)
            holder.player = null
        }
    }

    private fun hideAllSliders(){
        for(holder in viewHolders){
            holder.volumeSlider.visibility = View.INVISIBLE
        }
    }


    override fun getItemId(position: Int): Long = position.toLong()


    override fun uiCallback() {
        if (AudioController.controllerState == AudioController.ControllerState.PAUSE) {
            for (holder in viewHolders) {
                if (holder.selected) {
                    //parentFragment.colorBlink(R.color.green, R.color.blue, holder.itemView)
                }
            }
        } else {
            for (holder in viewHolders) {
                if (holder.selected) {
                    holder.itemView.clearAnimation()
                    holder.itemView.setBackgroundResource(R.color.green)
                }
            }
        }
        parentFragment.setButtonUI()
    }

    private class DiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean {
            return when {
                newItem.trackName != oldItem.trackName -> false
                newItem.isRecording != oldItem.isRecording -> false
                else -> true
            }
        }
    }


}


interface UiListener {
    fun uiCallback()
}








