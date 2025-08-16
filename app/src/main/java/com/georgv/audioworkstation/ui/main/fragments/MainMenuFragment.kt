package com.georgv.audioworkstation.ui.main.fragments
import android.content.res.Configuration
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.util.Log
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.MainMenuAdapter
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.customHandlers.Utilities
import com.georgv.audioworkstation.customHandlers.ViewAnimator
import com.georgv.audioworkstation.data.MenuItem
import com.georgv.audioworkstation.data.MenuItemType
import com.georgv.audioworkstation.data.Track
import com.georgv.audioworkstation.databinding.FragmentMainMenuBinding
import com.georgv.audioworkstation.engine.NativeAudioManager
import com.georgv.audioworkstation.ui.main.SongViewModel
import java.io.File

class MainMenuFragment : Fragment(), DialogCaller, MainMenuAdapter.OnMenuItemClickListener {
    private lateinit var binding: FragmentMainMenuBinding
    private val viewModel: SongViewModel by activityViewModels()
    private lateinit var menuRecyclerView: RecyclerView
    private lateinit var menuAdapter: MainMenuAdapter
    private var nativeAudio: NativeAudioManager? = null
    private var currentRecordingTrack: Track? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {

        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for this fragment
        binding = FragmentMainMenuBinding.inflate(inflater, container, false)
        menuRecyclerView = binding.menuRecyclerView
        menuAdapter = MainMenuAdapter(getMenuItems(),this)
        menuRecyclerView.adapter = menuAdapter

        // Initialize native audio manager
        nativeAudio = NativeAudioManager.from(this)

        binding.recordButton.setOnClickListener {
            Log.i("MainMenuFragment", "Record button clicked - listener triggered")
            onFastRecordButtonClick()
        }

        return binding.root
    }

    private fun getMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem("Create", R.drawable.logoo,MenuItemType.CREATE),
            MenuItem("Library", R.drawable.logoo,MenuItemType.LIBRARY),
            MenuItem("Devices", R.drawable.logoo,MenuItemType.DEVICES),
            MenuItem("Collaborate", R.drawable.logoo,MenuItemType.COLLABORATE)
        )
    }

    private fun onFastRecordButtonClick() {
        try {
            Log.i("MainMenuFragment", "Fast Record button clicked")
            
            // Create unique song name with timestamp
            val songName = "Recording_${System.currentTimeMillis()}"
            val trackName = "Track 1"
            
            Log.i("MainMenuFragment", "Starting Fast Record: $songName")
            
            // Create song and track in database
            val (song, track) = viewModel.createNewSongWithTrack(songName, trackName)
            
            if (song != null && track != null) {
                currentRecordingTrack = track
                Log.i("MainMenuFragment", "Song and track created: ${song.name}, ${track.name}")
                
                // Create WAV file path for recording
                val wavPath = Utilities.createWavFilePath(requireContext(), "${songName}_$trackName")
                Log.i("MainMenuFragment", "WAV path: $wavPath")
                
                // Update track with WAV path
                updateTrackWavPath(track.id, wavPath)
                
                // Start native recording
                nativeAudio?.let { audio ->
                    if (audio.startRecording(wavPath)) {
                        Log.i("MainMenuFragment", "Recording started successfully")
                        
                        // Navigate to song fragment
                        navigateToSong()
                    } else {
                        Log.e("MainMenuFragment", "Failed to start native recording")
                        // Still navigate even if recording fails
                        navigateToSong()
                    }
                } ?: run {
                    Log.e("MainMenuFragment", "Native audio manager not available")
                    // Still navigate even if native audio is not available
                    navigateToSong()
                }
            } else {
                Log.e("MainMenuFragment", "Failed to create song and track")
                // For debugging, try to navigate anyway
                navigateToSong()
            }
        } catch (e: Exception) {
            Log.e("MainMenuFragment", "Error in Fast Record", e)
            // Try to navigate anyway for debugging
            navigateToSong()
        }
    }
    
    private fun updateTrackWavPath(trackId: String, wavPath: String) {
        viewModel.updateTrackWavPath(trackId, wavPath)
    }

    private fun navigateToSong() {
        try {
            // Use the actual action ID from navigation.xml
            NavHostFragment.findNavController(this@MainMenuFragment).navigate(R.id.action_mainMenuFragment_to_song_Fragment)
            Log.i("MainMenuFragment", "Navigation to song fragment successful")
        } catch (e: Exception) {
            Log.e("MainMenuFragment", "Navigation failed", e)
        }
    }

    override fun onMenuItemClick(position: Int) {

    }

    override fun delegateFunctionToDialog(songName: String) {
        viewModel.createNewSong(songName,null)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }
}