package com.georgv.audioworkstation.core.audio

import android.Manifest
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RecordingCaptureServiceTest {

    @Test
    fun `manifest keeps microphone capture in a foreground service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val info =
            context.packageManager.getServiceInfo(
                ComponentName(context, RecordingCaptureService::class.java),
                PackageManager.GET_META_DATA,
            )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, info.foregroundServiceType)
        assertFalse(info.exported)
        val permissions =
            context.packageManager.getPackageInfo(
                context.packageName,
                PackageManager.GET_PERMISSIONS,
            ).requestedPermissions?.toSet().orEmpty()
        assertTruePermission(permissions, Manifest.permission.RECORD_AUDIO)
        assertTruePermission(permissions, Manifest.permission.FOREGROUND_SERVICE)
        assertTruePermission(permissions, Manifest.permission.FOREGROUND_SERVICE_MICROPHONE)
    }

    @Test
    fun `start posts the microphone foreground notification`() {
        val controller = Robolectric.buildService(RecordingCaptureService::class.java).create()
        controller.startCommand(0, 0)
        val service = controller.get()
        val notification = Shadows.shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        assertEquals(
            "Recording",
            notification.extras.getString(Notification.EXTRA_TITLE),
        )
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE, service.foregroundServiceType)
    }

    private fun assertTruePermission(permissions: Set<String>, permission: String) {
        org.junit.Assert.assertTrue(permissions.contains(permission))
    }
}
