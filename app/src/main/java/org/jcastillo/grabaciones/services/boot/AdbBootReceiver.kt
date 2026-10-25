/*
 * Grabaciones Locales boot recovery for previously paired wireless ADB.
 * GPL-3.0-or-later; see LICENSE.
 */
package org.jcastillo.grabaciones.services.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import org.jcastillo.grabaciones.data.AppPreferences
import org.jcastillo.grabaciones.utils.AppLogger

/** Re-enables Android's wireless-debugging switch after boot when the user previously paired. */
class AdbBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!AppPreferences(context).isAdbPaired()) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            AppLogger.w(TAG, "ADB pairing exists, but WRITE_SECURE_SETTINGS was not granted; reboot auto-recovery is unavailable")
            return
        }
        val enabled = runCatching { Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1) }.getOrDefault(false)
        AppLogger.i(TAG, if (enabled) "Wireless debugging re-enabled after ${intent.action}" else "Android refused to re-enable wireless debugging after ${intent.action}")
    }

    companion object {
        private const val TAG = "GL:AdbBootReceiver"
    }
}
