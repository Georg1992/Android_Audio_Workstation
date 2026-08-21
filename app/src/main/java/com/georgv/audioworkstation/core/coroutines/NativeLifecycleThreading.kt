package com.georgv.audioworkstation.core.coroutines

import com.georgv.audioworkstation.core.diagnostics.ThreadingDiagnostics

/** Debug-only guard for JNI calls that touch engine/Oboe lifecycle (not live gain/pan atomics). */
fun checkNotMainThreadForNativeLifecycle(operation: String) {
    ThreadingDiagnostics.warnIfMainThreadNativeLifecycle(operation)
}
