package com.georgv.audioworkstation.audio.effects

import java.nio.ByteBuffer

abstract class Effect {
    abstract fun apply(byteArray: ByteArray):ByteArray
    abstract fun apply(floatArray: FloatArray):FloatArray
}
