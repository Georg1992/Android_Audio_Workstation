package com.georgv.audioworkstation.data.model

data class MenuItem(val name: String, val iconResId: Int, val menuItemType:MenuItemType)
enum class MenuItemType {
    CREATE, LIBRARY, DEVICES, COLLABORATE
}
