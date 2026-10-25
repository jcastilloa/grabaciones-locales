/*
 * Grabaciones Locales: Binder hand-off for the embedded ADB shell daemon.
 * Adapted from CallVault's GPL-3.0-or-later Binder-delivery design; see THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.server

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import org.jcastillo.grabaciones.integrations.adb.EmbeddedAdbManager
import org.jcastillo.grabaciones.utils.AppLogger

/** Exported one-method endpoint; accepts a Binder only from Android's shell UID. */
class EmbeddedAdbBinderProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        if (method != METHOD_SEND_BINDER) return Bundle()
        val callingUid = Binder.getCallingUid()
        if (callingUid != SHELL_UID) {
            AppLogger.w(TAG, "Rejected recorder Binder delivery from uid=$callingUid")
            return Bundle()
        }
        val binder = extras?.getBinder(EXTRA_BINDER)
        if (binder == null) {
            AppLogger.e(TAG, "ADB daemon called without a Binder")
            return Bundle()
        }
        val appContext = context?.applicationContext ?: return Bundle()
        EmbeddedAdbManager.onBinderReceived(appContext, binder)
        return Bundle().apply { putBoolean(EXTRA_ACCEPTED, true) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val AUTHORITY_SUFFIX = ".recorder"
        const val METHOD_SEND_BINDER = "sendShellService"
        const val EXTRA_BINDER = "org.jcastillo.grabaciones.extra.SHELL_BINDER"
        const val EXTRA_ACCEPTED = "org.jcastillo.grabaciones.extra.ACCEPTED"
        private const val SHELL_UID = 2000
        private const val TAG = "GL:AdbBinderProvider"

        fun authority(packageName: String): String = packageName + AUTHORITY_SUFFIX

        val AUTHORITY: String get() = "org.jcastillo.grabaciones.recorder"
    }
}
