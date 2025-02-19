package com.georgv.audioworkstation.customHandlers

import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ArrayAdapter
import android.widget.ImageView
import android.widget.ListView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.R

class ViewAnimator {

    // Reusable method to animate a view
    fun animateView(view: View, scaleX: Float = 1f, scaleY: Float = 1f, translationX: Float = 0f, translationY: Float = 0f, alpha: Float = 1f, duration: Long = 300) {
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

    // Optional: You can add more specific animation methods if needed
    fun animateRecyclerView(recyclerView: RecyclerView, position: Int) {
        val clickedView = recyclerView.getChildAt(position) ?: return
        val itemX = clickedView.x
        val itemY = clickedView.y

        recyclerView.pivotX = itemX
        recyclerView.pivotY = itemY
        animateView(recyclerView, scaleX = 2f, scaleY = 2f, translationX = -itemX, translationY = -itemY)

        for (i in 0 until recyclerView.childCount) {
            if (i != position) {
                val child = recyclerView.getChildAt(i)
                val directionX = if (child.x < itemX) -child.width.toFloat() else child.width.toFloat()
                val directionY = if (child.y < itemY) -child.height.toFloat() else child.height.toFloat()

                animateView(child, translationX = directionX, translationY = directionY)
            }
        }
    }

    // Optional: Reverse animation for RecyclerView items
    fun reverseRecyclerViewAnimation(recyclerView: RecyclerView) {
        animateView(recyclerView, scaleX = 1f, scaleY = 1f, translationX = 0f, translationY = 0f)

        for (i in 0 until recyclerView.childCount) {
            val child = recyclerView.getChildAt(i)
            animateView(child, translationX = 0f, translationY = 0f)
        }
    }

    // Optional: Handle the expansion of the menu item
    fun expandMenuItem(clickedView: View, isReverse: Boolean) {
        val icon = clickedView.findViewById<ImageView>(R.id.menu_icon)
        val textView = clickedView.findViewById<TextView>(R.id.text_view)
        val listView = clickedView.findViewById<ListView>(R.id.expandable_list)

        if (isReverse) {
            animateView(icon, alpha = 1f)
            animateView(textView, translationY = 0f)
            animateView(listView, alpha = 0f, duration = 300)
            listView.visibility = View.GONE
        } else {
            animateView(icon, alpha = 0f)
            animateView(textView, translationY = -70f)
            listView.visibility = View.VISIBLE
            animateView(listView, alpha = 1f)

            val items = listOf("TEST", "TEST", "TEST", "TEST")
            val adapter = ArrayAdapter(clickedView.context, android.R.layout.simple_list_item_1, items)
            listView.adapter = adapter
        }
    }

    fun floatViewOutOfScreen(view: View, duration: Long = 500) {
        // Animate the view to move upwards off the screen
        view.animate()
            .translationY(-view.height.toFloat()) // Move view up
            .alpha(0f) // Fade out
            .setDuration(duration)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                view.visibility = View.GONE // Set the visibility to GONE after animation
            }
            .start()
    }
}