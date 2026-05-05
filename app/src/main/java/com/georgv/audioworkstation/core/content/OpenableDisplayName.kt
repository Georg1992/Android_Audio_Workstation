package com.georgv.audioworkstation.core.content

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns

fun resolveDisplayName(context: Context, uri: Uri): String? {
    val resolver = context.contentResolver
    val cursor = runCatching {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
    }.getOrNull() ?: return null
    cursor.use {
        if (!it.moveToFirst()) return null
        val columnIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
        if (columnIndex < 0) return null
        val rawName = it.getString(columnIndex) ?: return null
        return rawName.substringBeforeLast('.', rawName).ifBlank { null }
    }
}
