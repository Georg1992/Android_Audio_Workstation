package com.georgv.audioworkstation.core.audio

import android.os.StatFs
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StatFsRecordingStorageFsQuery @Inject constructor() : RecordingStorageFsQuery {

    override fun availableBytes(path: String): Long? =
        runCatching {
            val file = File(path)
            val statPath =
                when {
                    file.isDirectory -> path
                    else -> file.parentFile?.absolutePath
                } ?: return null
            StatFs(statPath).availableBytes
        }.getOrNull()
}
