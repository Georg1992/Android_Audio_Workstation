package com.georgv.audioworkstation.ui.screens.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.georgv.audioworkstation.core.audio.capability.DeviceLatencyReadApi
import com.georgv.audioworkstation.core.audio.capability.DeviceLatencySummary
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DevicesUiState(
    val loading: Boolean = true,
    val summary: DeviceLatencySummary? = null,
    val error: String? = null,
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    private val latencyReadApi: DeviceLatencyReadApi,
) : ViewModel() {
    private val _state = MutableStateFlow(DevicesUiState())
    val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _state.value = DevicesUiState(loading = true)
            runCatching { latencyReadApi.currentRouteSummary() }
                .onSuccess { summary ->
                    _state.value = DevicesUiState(loading = false, summary = summary)
                }
                .onFailure { error ->
                    _state.value =
                        DevicesUiState(
                            loading = false,
                            error = error.message ?: "Failed to load latency summary",
                        )
                }
        }
    }
}
