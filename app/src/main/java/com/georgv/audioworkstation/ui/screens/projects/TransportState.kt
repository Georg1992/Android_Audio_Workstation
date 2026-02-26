package com.georgv.audioworkstation.ui.screens.projects

sealed interface TransportState {
    data object Idle : TransportState
    data object Playing: TransportState
    data object Recording : TransportState
    data object Overdub : TransportState // playback + recording
}