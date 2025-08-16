package com.georgv.audioworkstation.ui.main.fragments
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.databinding.FragmentAudioControlsBinding
import com.georgv.audioworkstation.engine.NativeAudioManager
import com.georgv.audioworkstation.ui.main.SongViewModel


class AudioControlsFragment:Fragment() {
    private lateinit var binding:FragmentAudioControlsBinding
    private val viewModel: SongViewModel by activityViewModels()
    private var nativeAudio: NativeAudioManager? = null
    private var isRecording = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentAudioControlsBinding.inflate(inflater,container,false)
        
        // Initialize native audio manager
        nativeAudio = NativeAudioManager.from(this)

        val clickAnimation = AnimationUtils.loadAnimation(context, R.anim.button_click)
        showPlay()

        binding.playButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            onPlayClicked()
        }

        binding.pauseButton.setOnClickListener{
            it.startAnimation(clickAnimation)
            onPauseClicked()
        }

        binding.playPauseButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            onPlayPauseClicked()
        }

        binding.stopButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            onStopClicked()
        }

        binding.recordButton.setOnClickListener {
            it.startAnimation(clickAnimation)
            onRecordClicked()
        }
        
        // Check if already recording when fragment is created
        updateRecordingState()
        
        // Check if there's a track marked for recording from Fast Record
        checkForPendingRecording()

        return binding.root
    }
    
    private fun onPlayClicked() {
        Log.i("AudioControlsFragment", "Play clicked")
        val selectedTracks = viewModel.getSelectedTracks()
        if (selectedTracks.isEmpty()) {
            Log.i("AudioControlsFragment", "No tracks selected, play button idle")
            return
        }
        
        // Load selected tracks into native engine
        nativeAudio?.let { audio ->
            audio.clearTracks()
            selectedTracks.forEach { track ->
                if (!track.isRecording && track.wavFilePath.isNotEmpty()) {
                    audio.addTrack(track.wavFilePath, track.volume / 100f)
                    Log.i("AudioControlsFragment", "Added track to playback: ${track.name}")
                }
            }
            audio.loadTracks()
            
            if (audio.startPlayback()) {
                showPause()
                Log.i("AudioControlsFragment", "Started playback of ${selectedTracks.size} selected tracks")
            }
        }
    }
    
    private fun onPauseClicked() {
        Log.i("AudioControlsFragment", "Pause clicked")
        nativeAudio?.stopPlayback()
        showPlayPause()
    }
    
    private fun onPlayPauseClicked() {
        Log.i("AudioControlsFragment", "Play/Pause clicked")
        nativeAudio?.let { audio ->
            if (audio.startPlayback()) {
                showPause()
            }
        }
    }
    
    private fun onStopClicked() {
        Log.i("AudioControlsFragment", "Stop clicked")
        nativeAudio?.stopPlayback()
        if (isRecording) {
            stopRecording()
            // Update track in database to mark recording as finished
            finishRecordingInDatabase()
        }
        // Clear track selection after stopping
        viewModel.clearTrackSelection()
        showPlay()
    }
    
    private fun onRecordClicked() {
        Log.i("AudioControlsFragment", "Record clicked")
        if (isRecording) {
            stopRecording()
            finishRecordingInDatabase()
        } else {
            startNewRecording()
        }
    }
    
        private fun startNewRecording() {
        try {
            val currentSong = viewModel.currentSong.value
            if (currentSong == null) {
                Log.e("AudioControlsFragment", "No current song for recording")
                return
            }
            
            // Get selected tracks or create a new one if none exist
            val selectedTracks = viewModel.getSelectedTracks()
            val trackToRecord = if (selectedTracks.isNotEmpty()) {
                // Use the first selected track
                selectedTracks.first()
            } else {
                // Get existing tracks
                val existingTracks = viewModel.tracks.value
                if (existingTracks.isNotEmpty()) {
                    // Use the first existing track
                    existingTracks.first()
                } else {
                    // Create a new track only if none exist
                    val timestamp = System.currentTimeMillis()
                    val trackName = "Track_${timestamp % 10000}"
                    val recordingsDir = java.io.File(requireContext().filesDir, "recordings")
                    if (!recordingsDir.exists()) recordingsDir.mkdirs()
                    val wavPath = "${recordingsDir}/${trackName}.wav"
                    viewModel.createTrackForCurrentSong(trackName, wavPath)
                }
            }
            
            if (trackToRecord != null) {
                Log.i("AudioControlsFragment", "Starting recording for track: ${trackToRecord.name}")
                
                // Mark track as recording in database
                viewModel.startTrackRecording(trackToRecord.id)
                
                // Start native recording
                nativeAudio?.let { audio ->
                    if (audio.startRecording(trackToRecord.wavFilePath)) {
                        isRecording = true
                        updateRecordingState()
                        Log.i("AudioControlsFragment", "Recording started for track: ${trackToRecord.name}")
                    } else {
                        Log.e("AudioControlsFragment", "Failed to start native recording")
                    }
                }
            } else {
                Log.e("AudioControlsFragment", "Failed to find or create track for recording")
            }
        } catch (e: Exception) {
            Log.e("AudioControlsFragment", "Error starting recording", e)
        }
    }
    
    private fun stopRecording() {
        try {
            nativeAudio?.stopRecording()
            isRecording = false
            updateRecordingState()
            Log.i("AudioControlsFragment", "Recording stopped")
        } catch (e: Exception) {
            Log.e("AudioControlsFragment", "Error stopping recording", e)
        }
    }
    
    private fun updateRecordingState() {
        nativeAudio?.let { audio ->
            isRecording = audio.isRecording()
        }
        
        // Update record button appearance based on recording state
        if (isRecording) {
            binding.recordButton.setBackgroundResource(R.color.bright_green) // Recording indicator
        } else {
            binding.recordButton.setBackgroundResource(R.drawable.button_background) // Normal state
        }
    }
    
    private fun finishRecordingInDatabase() {
        val recordingTrack = viewModel.getRecordingTrack()
        if (recordingTrack != null) {
            val duration = System.currentTimeMillis() - recordingTrack.timeStampStart
            viewModel.finishTrackRecording(recordingTrack.id, duration)
            Log.i("AudioControlsFragment", "Finished recording for track: ${recordingTrack.name}")
            
            // Refresh tracks to update UI colors
            viewModel.loadTracksForCurrentSong()
        }
    }
    
    private fun checkForPendingRecording() {
        // Check if there's a track marked for recording (from Fast Record)
        val recordingTrack = viewModel.getRecordingTrack()
        if (recordingTrack != null && !isRecording) {
            Log.i("AudioControlsFragment", "Found track marked for recording: ${recordingTrack.name}")
            startRecordingForTrack(recordingTrack)
        }
    }
    
    private fun startRecordingForTrack(track: com.georgv.audioworkstation.data.Track) {
        try {
            nativeAudio?.let { audio ->
                if (audio.startRecording(track.wavFilePath)) {
                    isRecording = true
                    updateRecordingState()
                    Log.i("AudioControlsFragment", "Started recording for track: ${track.name}")
                } else {
                    Log.e("AudioControlsFragment", "Failed to start recording for track: ${track.name}")
                }
            }
        } catch (e: Exception) {
            Log.e("AudioControlsFragment", "Error starting recording for track", e)
        }
    }

    private fun showPlay() {
        binding.playPauseButton.visibility = View.GONE
        binding.pauseButton.visibility = View.GONE
        binding.playButton.visibility = View.VISIBLE
    }

    private fun showPlayPause() {
        binding.playButton.visibility = View.GONE
        binding.pauseButton.visibility = View.GONE
        binding.playPauseButton.visibility = View.VISIBLE
    }

    private fun showPause() {
        binding.playButton.visibility = View.GONE
        binding.playPauseButton.visibility = View.GONE
        binding.pauseButton.visibility = View.VISIBLE
    }

}