package org.jcastillo.grabaciones.integrations.adb

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jcastillo.grabaciones.services.adb.AdbPairingService
import org.jcastillo.grabaciones.system.permissions.PermissionChecks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdbPairingNotificationTest {
    @Test
    fun createsPairingChannelAndInlineCodeReplyAction() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AdbPairingNotifications.ensureChannel(context)

        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = manager.getNotificationChannel(AdbPairingNotifications.CHANNEL_ID)
        assertNotNull(channel)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel!!.importance)
        assertNull("Pairing status should not play a notification sound", channel.sound)
        assertTrue("Pairing channel should not create a badge", !channel.canShowBadge())

        val notification = AdbPairingNotifications.found(context, "192.168.1.20", 37123)
        assertEquals(AdbPairingNotifications.CHANNEL_ID, notification.channelId)
        assertTrue("The pairing reply notification must persist", notification.flags and Notification.FLAG_ONGOING_EVENT != 0)
        val replyAction = notification.actions.single { it.remoteInputs?.isNotEmpty() == true }
        val input = replyAction.remoteInputs.single()
        assertEquals(AdbPairingNotifications.REMOTE_INPUT_KEY, input.resultKey)

        val replyIntent = Intent()
        RemoteInput.addResultsToIntent(
            arrayOf(input),
            replyIntent,
            Bundle().apply { putCharSequence(input.resultKey, "123456") },
        )
        assertEquals("123456", AdbPairingNotifications.pairingCodeFromReply(replyIntent))
    }

    @Test
    fun manifestRequestsRuntimeNotificationPermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val requested = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty()
        assertTrue("Pairing notification requires POST_NOTIFICATIONS on Android 13+", requested.contains(android.Manifest.permission.POST_NOTIFICATIONS))
    }

    @Test
    fun startsAndStopsTheForegroundPairingService() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            AdbPairingService.start(context)
            SystemClock.sleep(750)
            @Suppress("DEPRECATION")
            val running = context.getSystemService(ActivityManager::class.java)
                .getRunningServices(100)
                .any { it.service.className == AdbPairingService::class.java.name }
            assertTrue("Pairing discovery should survive while Settings is in front", running)
            assertTrue("Settings must open only after the foreground notification is published", AdbPairingSession.state.value.notificationReady)
            if (PermissionChecks.hasNotificationPermission(context)) {
                assertTrue(
                    "The persistent pairing notification must be visible when notification permission is granted",
                    context.getSystemService(NotificationManager::class.java).activeNotifications
                        .any { it.id == AdbPairingNotifications.NOTIFICATION_ID },
                )
            }
        } finally {
            AdbPairingService.stop(context)
            SystemClock.sleep(250)
        }
    }

    @Test
    fun pairingServiceDeclaresSpecialUseForegroundType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        @Suppress("DEPRECATION")
        val service = context.packageManager.getServiceInfo(ComponentName(context, AdbPairingService::class.java), 0)
        assertTrue(
            "Pairing discovery must run as a special-use foreground service",
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE != 0,
        )
    }
}
