package com.georgv.audioworkstation.ui.main.fragments
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.georgv.audioworkstation.MainActivity
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.databinding.FragmentAudioControlsBinding
import com.georgv.audioworkstation.engine.NativeAudioManager
import com.georgv.audioworkstation.engine.AudioSessionManager
import com.georgv.audioworkstation.ui.main.SongViewModel


class AudioControlsFragment:Fragment() {
    private lateinit var binding:FragmentAudioControlsBinding
    private val viewModel: SongViewModel by activityViewModels()
    private var nativeAudio: NativeAudioManager? = null
    private var isRecording = false
    private val audioSession = AudioSessionManager.getInstance()

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
    
    override fun onResume() {
        super.onResume()
        Log.i("AudioControlsFragment", "onResume called")
        // Re-check for pending recording when returning to fragment
        checkForPendingRecording()
        // Also update UI state
        updateRecordingState()
    }
    
    private fun onPlayClicked() {
        Log.i("AudioControlsFragment", "Play clicked")
        
        // Get selected tracks for playback (instant access from AudioSessionManager)
        val selectedTracks = audioSession.getTracksForPlayback()
        Log.i("AudioControlsFragment", "Playing ${selectedTracks.size} selected tracks")
        
        if (selectedTracks.isEmpty()) {
            Log.i("AudioControlsFragment", "No tracks selected, play button idle")
            return
        }
        
        // Load selected tracks into native engine (using cached data for speed)
        nativeAudio?.let { audio ->
            audio.clearTracks()
            selectedTracks.forEach { trackData ->
                if (trackData.wavFilePath.isNotEmpty()) {
                    audio.addTrack(trackData.wavFilePath, trackData.volume / 100f)
                    Log.i("AudioControlsFragment", "Added track to playback: ${trackData.name}")
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
        val sessionIsRecording = audioSession.isRecording()
        val recordingTrack = audioSession.getRecordingTrack()
        
        Log.i("AudioControlsFragment", "Stop clicked:")
        Log.i("AudioControlsFragment", "  Local isRecording: $isRecording")
        Log.i("AudioControlsFragment", "  Session isRecording: $sessionIsRecording") 
        Log.i("AudioControlsFragment", "  Recording track: ${recordingTrack?.name}")
        
        // Stop any playback
        nativeAudio?.stopPlayback()
        
        // Always try to stop recording if there's a recording track
        if (sessionIsRecording || recordingTrack != null) {
            Log.i("AudioControlsFragment", "Stopping recording...")
            stopRecording()
            
            // Update track in database to mark recording as finished
            finishRecordingInDatabase()
            
            Log.i("AudioControlsFragment", "Recording stopped, updating UI")
        } else {
            Log.i("AudioControlsFragment", "No recording to stop, just stopping playback")
        }
        
        // Clear track selection after stopping
        viewModel.clearTrackSelection()
        
        // Force UI update to show play state
        updateRecordingState()
        
        Log.i("AudioControlsFragment", "Stop operation completed")
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
        // Check permission using MainActivity's centralized permission system
        val mainActivity = activity as? MainActivity
        if (mainActivity?.hasRecordPermission() != true) {
            Log.w("AudioControlsFragment", "🔒 RECORD_AUDIO permission not granted")
            Toast.makeText(requireContext(), "Audio recording permission required. Please restart the app and grant permission.", Toast.LENGTH_LONG).show()
            return
        }
        
        try {
            val currentSong = viewModel.currentSong.value
            if (currentSong == null) {
                Log.e("AudioControlsFragment", "No current song for recording")
                return
            }
            
                    // Check if there's already a recording ongoing (instant check)
        if (audioSession.isRecording()) {
            val recordingTrack = audioSession.getRecordingTrack()
            Log.w("AudioControlsFragment", "Recording already in progress for track: ${recordingTrack?.name}")
            return
        }
            
            // Always create a new track for recording
            val timestamp = System.currentTimeMillis()
            val trackName = "Track_${timestamp % 10000}"
            val recordingsDir = java.io.File(requireContext().filesDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()
            val wavPath = "${recordingsDir}/${trackName}.wav"
            
            // Create track in database (will be created with isRecording = true)
            val track = viewModel.createTrackForCurrentSong(trackName, wavPath)
            if (track != null) {
                Log.i("AudioControlsFragment", "Created new track for recording: ${track.name}")
                
                // Start native recording
                nativeAudio?.let { audio ->
                    if (audio.startRecording(wavPath)) {
                        // Sync local state with session state
                        updateRecordingState()
                        Log.i("AudioControlsFragment", "Recording started for track: ${track.name}")
                    } else {
                        Log.e("AudioControlsFragment", "Failed to start native recording")
                        // If native recording fails, mark track as not recording
                        viewModel.finishTrackRecording(track.id, 0)
                    }
                }
            } else {
                Log.e("AudioControlsFragment", "Failed to create track for recording")
            }
        } catch (e: Exception) {
            Log.e("AudioControlsFragment", "Error starting recording", e)
        }
    }
    
    private fun stopRecording() {
        try {
            nativeAudio?.stopRecording()
            // Update AudioSessionManager immediately for fast state access
            audioSession.stopRecording()
            // Sync local state with session state
            updateRecordingState()
            Log.i("AudioControlsFragment", "Recording stopped")
        } catch (e: Exception) {
            Log.e("AudioControlsFragment", "Error stopping recording", e)
        }
    }
    
    private fun finishRecordingInDatabase() {
        val recordingTrack = viewModel.getRecordingTrack()
        if (recordingTrack != null) {
            val duration = System.currentTimeMillis() - recordingTrack.timeStampStart
            viewModel.finishTrackRecording(recordingTrack.id, duration)
            Log.i("AudioControlsFragment", "Finished recording for track: ${recordingTrack.name}")
            
            // No need to manually refresh UI - reactive flows handle this automatically
            Log.i("AudioControlsFragment", "Database updated - UI will refresh automatically via reactive flows")
        } else {
            Log.w("AudioControlsFragment", "No recording track found to finish")
        }
    }
    
    private fun updateRecordingState() {
        // Sync local isRecording with AudioSessionManager state
        val sessionIsRecording = audioSession.isRecording()
        val recordingTrack = audioSession.getRecordingTrack()
        
        Log.i("AudioControlsFragment", "updateRecordingState:")
        Log.i("AudioControlsFragment", "  Session recording: $sessionIsRecording")
        Log.i("AudioControlsFragment", "  Local recording: $isRecording")
        Log.i("AudioControlsFragment", "  Recording track: ${recordingTrack?.name}")
        
        if (sessionIsRecording != isRecording) {
            isRecording = sessionIsRecording
            Log.i("AudioControlsFragment", "  ✅ Updated local recording state to: $isRecording")
        }
        
        // Update UI based on recording state
        if (isRecording) {
            Log.i("AudioControlsFragment", "  🔴 Calling showRecording()")
            showRecording()
        } else {
            Log.i("AudioControlsFragment", "  ⭕ Calling showPlay()")
            showPlay()
        }
    }
    
    private fun checkForPendingRecording() {
        // Check if there's a track marked for recording (from Fast Record)
        val recordingTrack = audioSession.getRecordingTrack()
        val sessionIsRecording = audioSession.isRecording()
        val nativeIsRecording = nativeAudio?.isRecording() ?: false
        
        Log.i("AudioControlsFragment", "checkForPendingRecording:")
        Log.i("AudioControlsFragment", "  Recording track: ${recordingTrack?.name}")
        Log.i("AudioControlsFragment", "  Local isRecording: $isRecording")
        Log.i("AudioControlsFragment", "  Session isRecording: $sessionIsRecording") 
        Log.i("AudioControlsFragment", "  Native isRecording: $nativeIsRecording")
        
        if (recordingTrack != null && !nativeIsRecording) {
            Log.i("AudioControlsFragment", "🎬 Starting pending recording for track: ${recordingTrack.name}")
            startRecordingForTrack(recordingTrack)
        } else if (recordingTrack == null) {
            Log.i("AudioControlsFragment", "No pending recording track found")
        } else {
            Log.i("AudioControlsFragment", "Recording already in progress, not starting new one")
        }
    }
    
    private fun startRecordingForTrack(trackData: AudioSessionManager.TrackData) {
        // Check permission using MainActivity's centralized permission system
        val mainActivity = activity as? MainActivity
        if (mainActivity?.hasRecordPermission() != true) {
            Log.w("AudioControlsFragment", "🔒 RECORD_AUDIO permission not granted for pending track")
            Toast.makeText(requireContext(), "Audio recording permission required. Please restart the app and grant permission.", Toast.LENGTH_LONG).show()
            return
        }
        
        try {
            nativeAudio?.let { audio ->
                if (audio.startRecording(trackData.wavFilePath)) {
                    // AudioSessionManager already has this track marked as recording
                    // Sync local state with session state
                    updateRecordingState()
                    Log.i("AudioControlsFragment", "✅ Started recording for track: ${trackData.name}")
                } else {
                    Log.e("AudioControlsFragment", "Failed to start recording for track: ${trackData.name}")
                    // If native fails, stop recording in session manager too
                    audioSession.stopRecording()
                }
            }
        } catch (e: Exception) {
            Log.e("AudioControlsFragment", "Error starting recording for track", e)
            audioSession.stopRecording()
        }
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
    
    private fun showPlay() {
        binding.pauseButton.visibility = View.GONE
        binding.playPauseButton.visibility = View.GONE
        binding.playButton.visibility = View.VISIBLE
        // Reset record button to normal state (not recording) - use default button background
        binding.recordButton.setBackgroundResource(R.drawable.button_background)
        Log.d("AudioControlsFragment", "UI: Showing play state - record button normal")
    }
    
    private fun showRecording() {
        binding.pauseButton.visibility = View.GONE
        binding.playPauseButton.visibility = View.GONE
        binding.playButton.visibility = View.VISIBLE
        // Set record button to recording state - use bright red/green
        binding.recordButton.setBackgroundResource(R.color.soft_red)
        Log.d("AudioControlsFragment", "UI: Showing recording state - record button RED")
    }

}