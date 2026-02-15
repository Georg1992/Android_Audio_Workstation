package com.georgv.audioworkstation.core.ui

import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.R

class ViewAnimator {

    var isAnimating = false

    // Reusable method to animate a view
    private fun animateView(
        view: View,
        translationX: Float? = null,
        translationY: Float? = null,
        scaleX: Float? = null,
        scaleY: Float? = null,
        alpha: Float? = null,
        duration: Long = 300,
        onEndAction: (() -> Unit)? = null
    ) {
        view.animate().apply {
            translationX?.let { translationX(it) }
            translationY?.let { translationY(it) }
            scaleX?.let { scaleX(it) }
            scaleY?.let { scaleY(it) }
            alpha?.let { alpha(it) }
            setDuration(duration)
            setInterpolator(AccelerateDecelerateInterpolator())
            onEndAction?.let { withEndAction(it) }
        }.start()
    }

    // Optional: You can add more specific animation methods if needed
    fun animateRecyclerView(recyclerView: RecyclerView, position: Int) {
        if (isAnimating) return
        isAnimating = true

        val pivotX = if (position % 2 == 0) 0f else recyclerView.width.toFloat()
        val pivotY = if (position < 2) 0f else recyclerView.height.toFloat()

        recyclerView.pivotX = pivotX
        recyclerView.pivotY = pivotY

        recyclerView.animate()
            .scaleX(2f)
            .scaleY(2f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withStartAction {
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i)
                    if (i != position) {
                        val directionX = when (i) {
                            1 -> 150f  // Library moves further right
                            2 -> 0f    // Devices moves further down
                            3 -> 150f  // Collaborate moves further diagonally right-down
                            else -> 0f
                        }
                        val directionY = when (i) {
                            2 -> 150f  // Devices moves further down
                            3 -> 150f  // Collaborate moves further diagonally right-down
                            else -> 0f
                        }
                        child.animate()
                            .translationX(directionX)
                            .translationY(directionY)
                            .setDuration(300)
                            .setInterpolator(AccelerateDecelerateInterpolator())
                            .start()
                    }
                }
            }
            .withEndAction { isAnimating = false }
            .start()
    }

    fun reverseRecyclerViewAnimation(recyclerView: RecyclerView) {
        if (isAnimating) return
        isAnimating = true

        recyclerView.animate()
            .scaleX(1f)
            .scaleY(1f)
            .translationX(0f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withStartAction {
                for (i in 0 until recyclerView.childCount) {
                    val child = recyclerView.getChildAt(i)
                    child.animate()
                        .translationX(0f)
                        .translationY(0f)
                        .setDuration(300)
                        .setInterpolator(AccelerateDecelerateInterpolator())
                        .start()
                }
            }
            .withEndAction { isAnimating = false }
            .start()
    }

    // Optional: Handle the expansion of the menu item





}
