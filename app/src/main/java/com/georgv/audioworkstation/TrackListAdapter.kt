package com.georgv.audioworkstation
import android.media.MediaPlayer
import android.os.Bundle
import android.view.*
import android.widget.TextView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.NavHostFragment.Companion.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.audioprocessing.AudioController
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.ui.main.EffectFragment
import com.georgv.audioworkstation.ui.main.MainFragment
import com.georgv.audioworkstation.ui.main.MainFragment.ButtonController.colorBlink
import com.georgv.audioworkstation.ui.main.MainFragmentDirections
import kotlinx.android.synthetic.main.track_view.view.*


private const val DEBUG_TAG = "Gestures"

class TrackListAdapter(val parentFragment: MainFragment) : ListAdapter<Track, TrackListAdapter.TrackViewHolder>(DiffCallback()),
    UiListener {
    init {
        setHasStableIds(true)
    }

    private val viewHolders: MutableList<TrackViewHolder> = mutableListOf()


    inner class TrackViewHolder(trackView: View) : RecyclerView.ViewHolder(trackView),
        View.OnClickListener {
        //private val mDetector: GestureDetectorCompat
        lateinit var track: Track
        var trackId:Long = 0
        lateinit var player: MediaPlayer

        var selected: Boolean = false
        val instrumentName: TextView = itemView.instrumentText
        var wavPath:String = ""
        var pcmPath:String = ""


        init {
            itemView.setOnClickListener(this)
            itemView.effectsButton.setOnClickListener {
                if(AudioController.controllerState == AudioController.ControllerState.STOP){
                    val action = MainFragmentDirections.actionTitleFragmentToEffectFragment(track)
                    findNavController(parentFragment).navigate(action)
                }

            }

//            mDetector = GestureDetectorCompat(trackView.context, MyGestureListener())
//            itemView.setOnTouchListener { v, event ->
//                mDetector.onTouchEvent(event)
//                true
//            }

            itemView.deleteButton.setOnClickListener {
                if(AudioController.controllerState == AudioController.ControllerState.STOP){
                    parentFragment.deleteTrack(this.trackId, this.pcmPath)
                }
            }
        }

        fun isPlayerInitialized() = ::player.isInitialized


        override fun onClick(p0: View?) {
            if (AudioController.controllerState == AudioController.ControllerState.STOP) {
                selected = !selected
                onBindViewHolder(this, adapterPosition)
            }
        }


//        private inner class MyGestureListener : GestureDetector.SimpleOnGestureListener() {
//
//            override fun onDown(event: MotionEvent): Boolean {
//                return true
//            }
//
//            override fun onSingleTapUp(e: MotionEvent): Boolean {
//                itemView.performClick()
//                return super.onSingleTapUp(e)
//            }
//
//            override fun onFling(
//                event1: MotionEvent,
//                event2: MotionEvent,
//                velocityX: Float,
//                velocityY: Float
//            ): Boolean {
//                Log.d(DEBUG_TAG, "onFling: $event1 $event2")
//                return true
//            }
//        }
}

    override fun onCreateViewHolder(parent: ViewGroup, position: Int): TrackViewHolder {
        val layoutInflater = LayoutInflater.from(parent.context)
        val view = layoutInflater.inflate(R.layout.track_view, parent, false)
        return TrackViewHolder(view)
    }

    override fun onBindViewHolder(holder: TrackViewHolder, position: Int) {
        val item = getItem(position)
        holder.track = item
        holder.instrumentName.text = item.trackName
        holder.trackId = item.id
        holder.pcmPath = item.pcmDir
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
                holder.player = MediaPlayer()
                holder.player.apply {
                    setDataSource(item.wavDir)
                }
                AudioController.playerList.add(holder.player)
            }
            false -> {
                if (holder.isPlayerInitialized()) {
                    holder.player.release()
                    AudioController.playerList.remove(holder.player)
                }
                holder.itemView.setBackgroundResource(R.color.blue)
            }
        }
        MainFragment.setPlayButton()
    }


    override fun getItemId(position: Int): Long = position.toLong()


    interface OnItemClickListener {
        fun onItemClick(position: Int, trackID: Long)
    }

    override fun uiCallback() {
        if (AudioController.controllerState == AudioController.ControllerState.PAUSE) {
            for (holder in viewHolders) {
                if (holder.selected) {
                    holder.itemView.colorBlink(R.color.green,R.color.blue,holder.itemView)
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

interface UiListener {
    fun uiCallback()
}







