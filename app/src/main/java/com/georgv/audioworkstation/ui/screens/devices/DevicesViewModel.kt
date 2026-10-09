package com.georgv.audioworkstation.ui.screens.devices

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class DevicesUiState(
    val sections: List<PhoneAudioSection> = emptyList(),
)

@HiltViewModel
class DevicesViewModel @Inject constructor(
    @ApplicationContext context: Context,
) : ViewModel() {
    private val audioManager = context.getSystemService(AudioManager::class.java)
        ?: error("AudioManager is required")
    private val devices = PhoneAudioDevices(audioManager)
    private var chosenOutputId: Int? = null
    private var chosenInputId: Int? = null
    private val _state = MutableStateFlow(DevicesUiState(devices.listing(chosenOutputId, chosenInputId)))
    internal val state: StateFlow<DevicesUiState> = _state.asStateFlow()

    private val callback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            publish()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            publish()
        }
    }

    init {
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
    }

    internal fun select(role: PhoneAudioRole, deviceId: Int) {
        when (role) {
            PhoneAudioRole.Output -> chosenOutputId = deviceId
            PhoneAudioRole.Input -> chosenInputId = deviceId
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            devices.select(deviceId)
        }
        publish()
    }

    override fun onCleared() {
        audioManager.unregisterAudioDeviceCallback(callback)
    }

    private fun publish() {
        _state.value = DevicesUiState(devices.listing(chosenOutputId, chosenInputId))
    }
}
