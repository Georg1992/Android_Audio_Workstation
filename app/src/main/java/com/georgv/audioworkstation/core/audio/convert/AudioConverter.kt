package com.georgv.audioworkstation.core.audio.convert

import java.io.File

/** Turns one audio file into another. Callers stay free of the codec. */
fun interface AudioConverter {
    fun convert(input: File): File
}
