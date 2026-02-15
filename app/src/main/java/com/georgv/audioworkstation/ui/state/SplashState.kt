package com.georgv.audioworkstation.ui.state

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

object SplashState {
    private val _visible = MutableStateFlow(true)
    val visible = _visible.asStateFlow()

    fun show() { _visible.value = true }
    fun hide() { _visible.value = false }
}


