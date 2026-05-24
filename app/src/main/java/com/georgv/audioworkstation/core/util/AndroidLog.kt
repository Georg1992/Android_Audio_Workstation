package com.georgv.audioworkstation.core.util

import android.util.Log

/** Safe for JVM unit tests where [Log] is not mocked. */
internal fun logWarning(tag: String, message: String, error: Throwable? = null) {
    runCatching {
        if (error != null) {
            Log.w(tag, message, error)
        } else {
            Log.w(tag, message)
        }
    }
}
