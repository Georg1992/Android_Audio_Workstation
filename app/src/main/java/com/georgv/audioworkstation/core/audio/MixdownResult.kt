package com.georgv.audioworkstation.core.audio

sealed class MixdownResult {
    data class Success(val outputPath: String) : MixdownResult()

    data object Failed : MixdownResult()

    data object Cancelled : MixdownResult()
}
