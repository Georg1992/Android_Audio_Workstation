package com.georgv.audioworkstation.online.share

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

interface CardImageStore {
    fun copy(uri: Uri): File
}

@Singleton
class AppCardImageStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) : CardImageStore {
    override fun copy(uri: Uri): File {
        val dest = File(context.cacheDir, "library-card-image")
        val input = context.contentResolver.openInputStream(uri) ?: error("could not read image")
        input.use { source -> dest.outputStream().use { source.copyTo(it) } }
        return dest
    }
}
