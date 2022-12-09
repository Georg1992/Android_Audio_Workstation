package com.georgv.audioworkstation.customHandlers

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

object Permissions {
    fun askForPermissions(perms: String?, activity: FragmentActivity) {
        if(perms == "RECORD_AUDIO") {
            activity.let {
                Log.d("perms 0", "asking for perms: ACCESS_FINE_LOCATION + ACTIVITY_RECOGNITION")
                if (ActivityCompat.checkSelfPermission(
                        it,
                        Manifest.permission.RECORD_AUDIO,
                    ) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    TODO()
                    return
                }
            }
        } else {
            Log.d("Permissions.kt","Could not find permission handler for: $perms")
        }
    }
}