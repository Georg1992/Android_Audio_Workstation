package com.georgv.audioworkstation.ui.main

import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.navigation.fragment.NavHostFragment
import com.georgv.audioworkstation.databinding.FragmentMainMenuBinding

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
        binding = FragmentMainMenuBinding.inflate(inflater,container,false)
        binding.toLoudSpeakerButton.setOnClickListener{
            val action = MainMenuFragmentDirections.actionMainMenuFragmentToLoudspeakerFragment()
            NavHostFragment.findNavController(this).navigate(action)
        }
        binding.toLibraryButton.setOnClickListener{
            val action = MainMenuFragmentDirections.actionMainMenuFragmentToLibraryFragment()
            NavHostFragment.findNavController(this).navigate(action)
        }
        binding.toTrackListButton.setOnClickListener {
            val action = MainMenuFragmentDirections.actionMainMenuFragmentToTrackListFragment()
            NavHostFragment.findNavController(this).navigate(action)
        }
        return binding.root
    }

    override fun delegateFunctionToDialog(songName: String) {
        TODO("Not yet implemented")
    }


}