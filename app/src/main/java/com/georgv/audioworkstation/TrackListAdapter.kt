package com.georgv.audioworkstation

import android.R.attr.radius
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.*
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.navigation.fragment.NavHostFragment.Companion.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.audioprocessing.AudioProcessor
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.TrackHolderViewBinding
import com.georgv.audioworkstation.ui.main.TrackListFragment
import com.georgv.audioworkstation.ui.main.TrackListFragmentDirections
import com.google.android.material.slider.Slider


private const val DEBUG_TAG = "Gestures"


class TrackListAdapter(val parentFragment: TrackListFragment) :
    ListAdapter<Track, TrackListAdapter.TrackViewHolder>(DiffCallback()) {

    private val viewHolders: MutableList<TrackViewHolder> = mutableListOf()

    init {
        hideAllSliders()
    }

    inner class TrackViewHolder(private val binding: TrackHolderViewBinding) :
        RecyclerView.ViewHolder(binding.root),
        View.OnClickListener {
        lateinit var track: Track
        var processor: AudioProcessor? = null
        var trackId: Long = 0
        var selected: Boolean = false
        val instrumentName: TextView = binding.instrumentText
        val volumeSlider = binding.slider
        var wavPath: String = ""

        init {
            itemView.setOnClickListener(this)
            binding.effectsButton.setOnClickListener {
                if (AudioController.controllerState == AudioController.ControllerState.STOP) {
                    val action =
                        TrackListFragmentDirections.actionTitleFragmentToEffectFragment()
                    findNavController(parentFragment).navigate(action)
                }
            }

            binding.deleteButton.setOnClickListener {
                if (AudioController.controllerState == AudioController.ControllerState.STOP) {
                    selected = false
                    AudioController.removeTrackFromTheTrackList(track)
                    parentFragment.deleteTrack(trackId, wavPath)
                    parentFragment.setButtonUI()
                }
            }

            binding.volumeButton.setOnClickListener {
                if (volumeSlider.isVisible) {
                    hideAllSliders()
                } else {
                    hideAllSliders()
                    volumeSlider.visibility = View.VISIBLE
                }
            }

            volumeSlider.stepSize = 1f
            volumeSlider.addOnChangeListener { _: Slider, fl: Float, _: Boolean ->
                processor?.controlVolume(fl)
            }

            volumeSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
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
        val itemBinding =
            TrackHolderViewBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return TrackViewHolder(itemBinding)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        Log.d("BINDING VIEWHOLDER", "UPDATING")
        val item = getItem(position)
        holder.track = item
        holder.trackId = item.id
        holder.instrumentName.text = item.trackName
        holder.volumeSlider.value = item.volume
        holder.wavPath = item.wavDir
        viewHolders.add(holder)
        if (item.isRecording == true) {
            val gradientDrawable = GradientDrawable()
            val color =
                ContextCompat.getColor(parentFragment.requireContext(), R.color.redTransparent)
            gradientDrawable.setColor(color)
            gradientDrawable.cornerRadius = radius.toFloat()
            gradientDrawable.setStroke(3, Color.BLACK)
            holder.itemView.background = gradientDrawable

            holder.selected = false
            return
        }
        when (holder.selected) {
            true -> {
                val processor = AudioProcessor()
                processor.setTrackToProcessor(item)
                holder.processor = processor
                AudioController.addTrackToTheTrackList(item, holder.processor)
                val gradientDrawable = GradientDrawable()
                val color = ContextCompat.getColor(parentFragment.requireContext(), R.color.green)
                gradientDrawable.setColor(color)
                gradientDrawable.cornerRadius = radius.toFloat()
                gradientDrawable.setStroke(3, Color.BLACK)
                holder.itemView.background = gradientDrawable
            }
            false -> {
                holder.processor = null
                AudioController.removeTrackFromTheTrackList(item)
                val gradientDrawable = GradientDrawable()
                val color = ContextCompat.getColor(parentFragment.requireContext(), R.color.blue)
                gradientDrawable.setColor(color)
                gradientDrawable.cornerRadius = radius.toFloat()
                gradientDrawable.setStroke(3, Color.BLACK)
                holder.itemView.background = gradientDrawable
            }
        }
        parentFragment.setButtonUI()
    }


    private fun hideAllSliders() {
        for (holder in viewHolders) {
            holder.volumeSlider.visibility = View.INVISIBLE
        }
    }


    override fun getItemId(position: Int): Long = position.toLong()


    private class DiffCallback : DiffUtil.ItemCallback<Track>() {
        override fun areItemsTheSame(oldItem: Track, newItem: Track): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: Track, newItem: Track): Boolean {
            return when {
                newItem.trackName != oldItem.trackName -> false
                newItem.isRecording != oldItem.isRecording -> false
                newItem.reverb != oldItem.reverb -> false
                newItem.equalizer != oldItem.equalizer -> false
                newItem.compressor != oldItem.compressor -> false
                else -> true
            }
        }
    }


}


interface UiListener {
    fun uiCallback()
    fun setValueFromUi(float: Float)
}








