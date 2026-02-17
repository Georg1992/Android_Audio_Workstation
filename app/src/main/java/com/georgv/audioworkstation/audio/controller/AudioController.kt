package com.georgv.audioworkstation.audio.controller


import com.georgv.audioworkstation.audio.processing.AudioProcessor

import androidx.fragment.app.FragmentActivity
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

object AudioController {
    enum class ControllerState {
        REC,
        PLAY,
        CONTINUE,
        PLAY_REC,
        STOP,
        PAUSE
    }


}



