package com.georgv.audioworkstation.core.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.IOException

object Utilities {
    fun createWavFilePath(context: Context, songName: String): String {
        val musicDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: throw IOException("External storage not available")
        val safeName = songName.replace("""[^\w\d_-]""".toRegex(), "_")
        return File(musicDir, "$safeName.wav").absolutePath
    }
}
