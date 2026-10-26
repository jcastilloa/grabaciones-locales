/*
 * MEGA backup settings and installation-scoped folder identity.
 * Uses only non-sensitive preferences; the MEGA session is encrypted separately.
 */
package org.jcastillo.grabaciones.integrations.mega

import android.content.Context
import android.os.Build
import androidx.core.content.edit
import java.util.Locale
import java.util.UUID

class MegaBackupPreferences(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) = prefs.edit { putBoolean(KEY_ENABLED, enabled) }
    fun isWifiOnly(): Boolean = prefs.getBoolean(KEY_WIFI_ONLY, DEFAULT_WIFI_ONLY)
    fun setWifiOnly(wifiOnly: Boolean) = prefs.edit { putBoolean(KEY_WIFI_ONLY, wifiOnly) }

    fun deviceFolderName(): String {
        val id = synchronized(this) {
            prefs.getString(KEY_INSTALLATION_ID, null) ?: UUID.randomUUID().toString()
                .replace("-", "")
                .take(6)
                .also { generated -> prefs.edit { putString(KEY_INSTALLATION_ID, generated) } }
        }
        return deviceFolderName(Build.MANUFACTURER, Build.MODEL, id)
    }

    companion object {
        private const val PREFERENCES = "mega_backup_settings"
        private const val KEY_ENABLED = "backup_enabled"
        private const val KEY_WIFI_ONLY = "wifi_only"
        internal const val DEFAULT_WIFI_ONLY = false
        private const val KEY_INSTALLATION_ID = "installation_id"
        private const val MAX_FOLDER_LENGTH = 64

        internal fun deviceFolderName(manufacturer: String, model: String, installationId: String): String {
            val maker = manufacturer.toDeviceToken().ifBlank { "Android" }
            val device = model.toDeviceToken().ifBlank { "Dispositivo" }
            val id = installationId.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }.take(8).ifBlank { "000000" }
            val prefix = "$maker-$device".take(MAX_FOLDER_LENGTH - id.length - 1).trimEnd('-')
            return "$prefix-$id"
        }

        private fun String.toDeviceToken(): String =
            trim().replace(Regex("[^A-Za-z0-9]+"), "-").trim('-')
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }
}
