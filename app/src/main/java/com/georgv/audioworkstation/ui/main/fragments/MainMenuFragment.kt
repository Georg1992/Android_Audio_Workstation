package com.georgv.audioworkstation.ui.main.fragments
import android.content.res.Configuration
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.ui.adapters.MainMenuAdapter
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.core.util.Utilities
import com.georgv.audioworkstation.core.ui.ViewAnimator
import com.georgv.audioworkstation.data.model.MenuItem
import com.georgv.audioworkstation.data.model.MenuItemType
import com.georgv.audioworkstation.databinding.FragmentMainMenuBinding
import com.georgv.audioworkstation.ui.main.SongViewModel

class MainMenuFragment : Fragment(), DialogCaller, MainMenuAdapter.OnMenuItemClickListener {
    private lateinit var binding: FragmentMainMenuBinding
    private val viewModel: SongViewModel by activityViewModels ()
    private lateinit var menuRecyclerView: RecyclerView
    private lateinit var menuAdapter: MainMenuAdapter

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


        binding.recordButton.setOnClickListener {
            onFastRecordButtonClick()
            val songName = "Song_${System.currentTimeMillis()}"
            val wavPath = Utilities.createWavFilePath(requireContext(), songName)
            viewModel.createNewSong(songName,wavPath)
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
        navigateToSong()
    }

    private fun navigateToSong() {
        val action = MainMenuFragmentDirections.actionMainMenuFragmentToSongFragment()
        NavHostFragment.findNavController(this@MainMenuFragment).navigate(action)
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
