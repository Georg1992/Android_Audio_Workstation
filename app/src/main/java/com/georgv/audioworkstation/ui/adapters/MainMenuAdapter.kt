package com.georgv.audioworkstation.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.georgv.audioworkstation.data.model.MenuItem
import com.georgv.audioworkstation.databinding.MenuItemBinding
import com.georgv.audioworkstation.ui.main.fragments.MainMenuFragment

class MainMenuAdapter(
    private val menuItems: List<MenuItem>,
    private val listener: OnMenuItemClickListener
) : RecyclerView.Adapter<MainMenuAdapter.MenuViewHolder>() {

    inner class MenuViewHolder(private val binding: MenuItemBinding) :
        RecyclerView.ViewHolder(binding.root),
        View.OnClickListener {
        init {
            this.itemView.setOnClickListener(this)
        }

        fun bind(menuItem: MenuItem) {
            binding.menuItemName.text = menuItem.name
            binding.menuIcon.setImageResource(menuItem.iconResId)
        }

        override fun onClick(v: View?) {
            listener.onMenuItemClick(adapterPosition)

        }


    }



    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): MenuViewHolder {
        val binding: MenuItemBinding =
            MenuItemBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        binding.root.post {
            val desiredHeight = parent.height / 2  // half of RecyclerView's height
            binding.root.layoutParams.height = desiredHeight
            binding.root.requestLayout()
        }
        return MenuViewHolder(binding)
    }

    override fun onBindViewHolder(holder: MenuViewHolder, position: Int) {
        val item = menuItems[position]
        holder.bind(item)
    }

    override fun getItemCount(): Int = menuItems.size

    interface OnMenuItemClickListener {
        fun onMenuItemClick(position: Int)
    }

}
