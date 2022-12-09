package com.georgv.audioworkstation.audioprocessing

abstract class Effect {
    abstract fun apply(filePath:String)
}