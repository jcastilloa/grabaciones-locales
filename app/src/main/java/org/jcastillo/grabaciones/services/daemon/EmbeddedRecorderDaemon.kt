/*
 * Grabaciones Locales shell daemon, launched as UID 2000 by embedded libadb-android.
 * Architecture adapted from CallVault; GPL-3.0-or-later. See THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.services.daemon

import android.os.Looper
import android.os.Process
import androidx.annotation.Keep
import org.jcastillo.grabaciones.server.EmbeddedAdbBinderDelivery
import org.jcastillo.grabaciones.server.EmbeddedAdbBinderProvider
import org.jcastillo.grabaciones.services.shell.ShellService
import org.jcastillo.grabaciones.utils.AppLogger

/** Entry point invoked by `app_process`; delivers the recorder AIDL Binder to the app process. */
@Keep
class EmbeddedRecorderDaemon {
    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            Thread.currentThread().name = "GrabacionesLocales-Shell"
            if (Process.myUid() != SHELL_UID) {
                android.util.Log.e(TAG, "Refusing to run recorder daemon outside shell UID: ${Process.myUid()}")
                return
            }
            Looper.prepareMainLooper()
            val authority = args.firstOrNull() ?: EmbeddedAdbBinderProvider.AUTHORITY
            try {
                val service = ShellService()
                binderService = service // Keep the Binder Stub strongly reachable for the daemon lifetime.
                if (!EmbeddedAdbBinderDelivery.deliver(service.asBinder(), authority)) {
                    android.util.Log.e(TAG, "App did not accept the recorder Binder")
                    return
                }
                android.util.Log.i(TAG, "Recorder Binder delivered; entering persistent shell process")
                Looper.loop()
            } catch (e: Throwable) {
                AppLogger.e(TAG, "Recorder daemon failed before Binder delivery", e)
            }
        }

        @Volatile
        private var binderService: ShellService? = null

        private const val SHELL_UID = 2000
        private const val TAG = "GL:RecorderDaemon"
    }
}
