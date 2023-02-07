package com.georgv.audioworkstation.audioprocessing

import java.nio.ByteBuffer

abstract class Effect {
    abstract fun apply(byteArray: ByteArray):ByteArray
    abstract fun apply(floatArray: FloatArray):FloatArray
}