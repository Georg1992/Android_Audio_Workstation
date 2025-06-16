package com.georgv.audioworkstation.ui.main.fragments

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment

class LoadingScreenFragment : Fragment() {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // You can also add logic here, e.g., show a message or animation
    }

    companion object {
        fun newInstance(): LoadingScreenFragment {
            return LoadingScreenFragment()
        }
    }
}
