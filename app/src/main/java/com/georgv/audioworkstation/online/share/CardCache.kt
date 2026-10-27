package com.georgv.audioworkstation.online.share

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface CardCache {
    fun audioFile(hash: String): File

    fun imageFile(hash: String): File
}

@Singleton
class AppCardCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CardCache {
    override fun audioFile(hash: String): File = file("community-audio", hash)

    override fun imageFile(hash: String): File = file("community-images", hash)

    private fun file(folder: String, name: String): File {
        val dir = File(context.cacheDir, folder)
        if (!dir.isDirectory && !dir.mkdirs()) error("could not create $folder")
        return File(dir, name)
    }
}
