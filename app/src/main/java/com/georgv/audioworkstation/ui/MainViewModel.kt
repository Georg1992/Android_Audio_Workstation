package com.georgv.audioworkstation.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.ui.state.AppUiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class MainViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(AppUiState())
    val uiState: StateFlow<AppUiState> = _uiState.asStateFlow()

    init {
        // TODO: сюда позже добавишь реальные init задачи:
        // - settings load
        // - realm open/migrations
        // - audio engine warmup
        viewModelScope.launch {
            delay(250) // минимально, чтобы не было "blink"
            _uiState.update { it.copy(showSplash = false) }
        }
    }

    fun showSplash() {
        _uiState.update { it.copy(showSplash = true) }
    }

    fun hideSplash() {
        _uiState.update { it.copy(showSplash = false) }
    }
}


