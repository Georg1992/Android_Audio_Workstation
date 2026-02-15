package com.georgv.audioworkstation.core.permissions

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts

/**
 * Simple permission requester that lets UI ask for permissions only when needed.
 * Keeps the callback logic out of MainActivity UI code.
 */
class PermissionRequester(
    private val activity: ComponentActivity
) {
    private var onGranted: (() -> Unit)? = null
    private var onDenied: ((denied: Set<String>) -> Unit)? = null

    private val launcher: ActivityResultLauncher<Array<String>> =
        activity.registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val denied = result.filterValues { granted -> !granted }.keys
            if (denied.isEmpty()) onGranted?.invoke() else onDenied?.invoke(denied)
            onGranted = null
            onDenied = null
        }

    fun ensure(
        perms: Array<String>,
        onGranted: () -> Unit,
        onDenied: (denied: Set<String>) -> Unit = {}
    ) {
        val missing = Permissions.missing(activity, perms)
        if (missing.isEmpty()) {
            onGranted()
            return
        }
        this.onGranted = onGranted
        this.onDenied = onDenied
        launcher.launch(missing.toTypedArray())
    }

    companion object {
        fun hasAll(context: Context, perms: Array<String>): Boolean =
            Permissions.missing(context, perms).isEmpty()
    }
}
