package com.georgv.audioworkstation.ui.main

import android.content.res.Configuration
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.navigation.fragment.NavHostFragment
import com.georgv.audioworkstation.databinding.FragmentMainMenuBinding
import com.georgv.audioworkstation.ui.main.dialogs.CreateSongDialogFragment

class MainMenuFragment : Fragment(), DialogCaller {
    private lateinit var binding: FragmentMainMenuBinding

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
        adjustGridLayout(resources.configuration.orientation)
        setMenuIconsAndText()

        binding.btnCreate.root.setOnClickListener {
            val manager = parentFragmentManager
            val dialog = CreateSongDialogFragment(this)
            dialog.show(manager,"CREATE NEW SONG")
//            val action = MainMenuFragmentDirections.actionMainMenuFragmentToTrackListFragment()
//            NavHostFragment.findNavController(this).navigate(action)
        }
        return binding.root
    }


    private fun setMenuIconsAndText() {
        binding.btnCreate.menuIcon.setImageResource(com.georgv.audioworkstation.R.drawable.logoo)
        binding.btnCreate.textView.text =
            getString(com.georgv.audioworkstation.R.string.menuCreateButton)
        binding.btnLibrary.menuIcon.setImageResource(com.georgv.audioworkstation.R.drawable.logoo)
        binding.btnLibrary.textView.text =
            getString(com.georgv.audioworkstation.R.string.menuLibraryButton)
        binding.btnDevices.menuIcon.setImageResource(com.georgv.audioworkstation.R.drawable.logoo)
        binding.btnDevices.textView.text =
            getString(com.georgv.audioworkstation.R.string.menuDevicesButton)
        binding.btnCommunity.menuIcon.setImageResource(com.georgv.audioworkstation.R.drawable.logoo)
        binding.btnCommunity.textView.text =
            getString(com.georgv.audioworkstation.R.string.menuCommunityButton)
    }

    private fun adjustGridLayout(orientation: Int) {
        if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
            binding.gridLayout.columnCount = 4
            binding.gridLayout.rowCount = 1
        } else if (orientation == Configuration.ORIENTATION_PORTRAIT) {
            binding.gridLayout.columnCount = 2
            binding.gridLayout.rowCount = 2
        }

    }

    override fun delegateFunctionToDialog(songName: String) {

    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        adjustGridLayout(newConfig.orientation)
    }
}