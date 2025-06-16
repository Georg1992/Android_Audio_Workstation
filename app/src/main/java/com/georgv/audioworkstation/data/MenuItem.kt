package com.georgv.audioworkstation.data

data class MenuItem(val name: String, val iconResId: Int, val menuItemType:MenuItemType)
enum class MenuItemType {
    CREATE, LIBRARY, DEVICES, COLLABORATE
}