package com.georgv.audioworkstation.ui.main
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.activity.addCallback
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.NavHostFragment
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.MainMenuAdapter
import com.georgv.audioworkstation.R
import com.georgv.audioworkstation.customHandlers.ViewAnimator
import com.georgv.audioworkstation.databinding.FragmentMainMenuBinding

class MainMenuFragment : Fragment(), DialogCaller, MainMenuAdapter.OnMenuItemClickListener {
    private lateinit var binding: FragmentMainMenuBinding
    private val viewModel: SongViewModel by activityViewModels ()
    data class MenuItem(val name: String, val iconResId: Int)
    private lateinit var recyclerView: RecyclerView
    private lateinit var menuAdapter: MainMenuAdapter
    private val viewAnimator = ViewAnimator()
    private var lastClickedPosition: Int? = null

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
        recyclerView = binding.menuRecyclerView
        menuAdapter = MainMenuAdapter(getMenuItems(),this)
        recyclerView.adapter = menuAdapter

        checkIfLoading()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner) {
            lastClickedPosition?.let { position ->
                viewAnimator.reverseRecyclerViewAnimation(recyclerView)
                val clickedView = binding.menuRecyclerView.findViewHolderForAdapterPosition(position)?.itemView
                clickedView?.let { viewAnimator.expandMenuItem(it, true) }
            }
        }

        binding.fastRecordButton.setOnClickListener {
            onFastRecordButtonClick()
        }

        return binding.root
    }

    private fun getMenuItems(): List<MenuItem> {
        return listOf(
            MenuItem("Create", R.drawable.logoo),
            MenuItem("Library", R.drawable.logoo),
            MenuItem("Devices", R.drawable.logoo),
            MenuItem("Community", R.drawable.logoo)
        )
    }

    private fun onFastRecordButtonClick() {
        // Get the view you want to animate (e.g., the menu container)
        val menuView = binding.menuRecyclerView // Or whatever the view is

        // Animate the menu view to float out of the screen
        viewAnimator.floatViewOutOfScreen(menuView)

        // Wait for the animation to complete and navigate to SongFragment
        Handler(Looper.getMainLooper()).postDelayed({
            navigateToSong() // Trigger the navigation after the animation completes
        }, 500) // Delay should match the animation duration
    }

    private fun checkIfLoading() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            if (isLoading) {
                navigateToSong()
            }
        }
    }

    private fun navigateToSong() {
        val action = MainMenuFragmentDirections.actionMainMenuFragmentToSongFragment()
        NavHostFragment.findNavController(this@MainMenuFragment).navigate(action)
    }

    // Helper function for common animation
    private fun animateView(view: View, scaleX: Float = 1f, scaleY: Float = 1f, translationX: Float = 0f, translationY: Float = 0f, alpha: Float = 1f, duration: Long = 300) {
        view.animate()
            .scaleX(scaleX)
            .scaleY(scaleY)
            .translationX(translationX)
            .translationY(translationY)
            .alpha(alpha)
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }






    override fun onMenuItemClick(position: Int) {
        lastClickedPosition = position
        viewAnimator.animateRecyclerView(recyclerView, position)
        val clickedView = binding.menuRecyclerView.findViewHolderForAdapterPosition(position)?.itemView
        clickedView?.let { viewAnimator.expandMenuItem(it, false) }
    }



    override fun delegateFunctionToDialog(songName: String) {
        viewModel.createNewSong(songName,null)

    }


    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)

    }


}