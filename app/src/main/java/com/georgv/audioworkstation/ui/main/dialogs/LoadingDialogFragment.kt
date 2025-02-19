package com.georgv.audioworkstation.ui.main.dialogs

import android.animation.ObjectAnimator
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.DialogFragment
import com.georgv.audioworkstation.databinding.FragmentDialogLoadingBinding
import com.georgv.audioworkstation.databinding.FragmentMainMenuBinding

class LoadingDialogFragment : DialogFragment() {

    private lateinit var binding: FragmentDialogLoadingBinding

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // Inflate the layout for the dialog
        binding = FragmentDialogLoadingBinding.inflate(inflater,container,false)

        // Set up the logo animation (you can use an ImageView and animate it)
        val logo = binding.loadingLogo
        val rotateAnimation = ObjectAnimator.ofFloat(logo, "rotation", 0f, 360f)
        rotateAnimation.duration = 1000 // 1 second per full rotation
        rotateAnimation.repeatCount = ObjectAnimator.INFINITE
        rotateAnimation.start()

        // Set dialog background to transparent so the background is white
        dialog?.window?.setBackgroundDrawable(ColorDrawable(Color.WHITE))

        return binding.root
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        return super.onCreateDialog(savedInstanceState).apply {
            setCancelable(false) // Prevent it from being dismissed accidentally
        }
    }
}
