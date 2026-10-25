/*
 * ADB pairing notification UX adapted from CallVault's GPL-3.0-or-later pairing service.
 * See THIRD_PARTY_NOTICES.md and LICENSE.
 */
package org.jcastillo.grabaciones.integrations.adb

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import org.jcastillo.grabaciones.MainActivity
import org.jcastillo.grabaciones.R
import org.jcastillo.grabaciones.services.adb.AdbPairingService

/** Creates the channel and builds the foreground/inline-reply notifications for pairing. */
object AdbPairingNotifications {
    const val CHANNEL_ID = "adb_pairing"
    const val NOTIFICATION_ID = 4712
    const val REMOTE_INPUT_KEY = "pairing_code"
    const val EXTRA_PORT = "pairing_port"
    const val EXTRA_HOST = "pairing_host"
    const val ACTION_START = "org.jcastillo.grabaciones.adb.PAIRING_START"
    const val ACTION_REPLY = "org.jcastillo.grabaciones.adb.PAIRING_REPLY"
    const val ACTION_STOP = "org.jcastillo.grabaciones.adb.PAIRING_STOP"

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_pairing_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                setShowBadge(false)
                description = context.getString(R.string.notif_pairing_channel_description)
            },
        )
    }

    fun searching(context: Context): Notification = builder(context)
        .setContentTitle(context.getString(R.string.notif_pairing_searching_title))
        .setContentText(context.getString(R.string.notif_pairing_searching_text))
        .setOngoing(true)
        .addAction(stopAction(context))
        .build()

    fun waitingForWirelessDebugging(context: Context): Notification = builder(context)
        .setContentTitle(context.getString(R.string.notif_pairing_waiting_wd_title))
        .setContentText(context.getString(R.string.notif_pairing_waiting_wd_text))
        .setOngoing(true)
        .addAction(stopAction(context))
        .build()

    fun found(context: Context, host: String, port: Int, error: String? = null): Notification = builder(context)
        .setContentTitle(
            context.getString(if (error == null) R.string.notif_pairing_found_title else R.string.notif_pairing_failed_title),
        )
        .setContentText(error ?: context.getString(R.string.notif_pairing_found_text))
        .setStyle(Notification.BigTextStyle().bigText(error ?: context.getString(R.string.notif_pairing_found_text)))
        .setOngoing(true)
        .addAction(replyAction(context, host, port))
        .addAction(stopAction(context))
        .build()

    fun working(context: Context): Notification = builder(context)
        .setContentTitle(context.getString(R.string.notif_pairing_working_title))
        .setContentText(context.getString(R.string.notif_pairing_working_text))
        .setOngoing(true)
        .setProgress(0, 0, true)
        .build()

    fun result(context: Context, title: String, text: String, openApp: Boolean): Notification = builder(context)
        .setContentTitle(title)
        .setContentText(text)
        .setStyle(Notification.BigTextStyle().bigText(text))
        .setAutoCancel(true)
        .apply { if (openApp) setContentIntent(openAppIntent(context)) }
        .build()

    fun pairingCodeFromReply(intent: Intent): String? =
        RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REMOTE_INPUT_KEY)?.toString()

    private fun builder(context: Context) = Notification.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_stat_adb)
        .setCategory(Notification.CATEGORY_SERVICE)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setOnlyAlertOnce(true)

    private fun stopAction(context: Context): Notification.Action {
        val intent = Intent(context, AdbPairingService::class.java).setAction(ACTION_STOP)
        val pendingIntent = PendingIntent.getService(
            context,
            4713,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, context.getString(R.string.notif_pairing_action_stop), pendingIntent).build()
    }

    private fun replyAction(context: Context, host: String, port: Int): Notification.Action {
        val input = RemoteInput.Builder(REMOTE_INPUT_KEY)
            .setLabel(context.getString(R.string.notif_pairing_remote_input_label))
            .setAllowFreeFormInput(true)
            .build()
        val intent = Intent(context, AdbPairingService::class.java)
            .setAction(ACTION_REPLY)
            .putExtra(EXTRA_HOST, host)
            .putExtra(EXTRA_PORT, port)
        val pendingIntent = PendingIntent.getForegroundService(
            context,
            4714,
            intent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(null, context.getString(R.string.notif_pairing_action_enter_code), pendingIntent)
            .addRemoteInput(input)
            .build()
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(
            context,
            4715,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
