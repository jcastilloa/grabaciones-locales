/*
 * Grabaciones Locales: embedded wireless-ADB integration adapted from CallVault.
 * CallVault source copyright: CallVault Authors; GPL-3.0-or-later. See LICENSE and THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.integrations.adb

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jcastillo.grabaciones.IShellService
import org.jcastillo.grabaciones.data.AppPreferences
import org.jcastillo.grabaciones.server.EmbeddedAdbBinderProvider
import org.jcastillo.grabaciones.utils.AppLogger
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/** Owns the persisted wireless-ADB identity, shell connection and recorder Binder lifecycle. */
object EmbeddedAdbManager {
    private const val TAG = "GL:EmbeddedAdb"
    private const val CONNECT_DISCOVERY_TIMEOUT_MS = 12_000L
    private const val BINDER_WAIT_TIMEOUT_MS = 18_000L
    private const val DAEMON_CLASS = "org.jcastillo.grabaciones.services.daemon.EmbeddedRecorderDaemon"
    private const val SERVICE_LOST_ERROR = "Se perdió el servicio ADB integrado. Comprueba la depuración inalámbrica y vuelve a emparejar si hace falta."

    private val launchMutex = Mutex()
    private val serviceLostListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var shellService: IShellService? = null

    @Volatile
    private var linkedBinder: IBinder? = null

    fun isPaired(context: Context): Boolean = AppPreferences(context).isAdbPaired()

    fun isRecorderConnected(): Boolean = shellService?.asBinder()?.isBinderAlive == true

    fun addServiceLostListener(listener: () -> Unit) {
        serviceLostListeners += listener
    }

    fun removeServiceLostListener(listener: () -> Unit) {
        serviceLostListeners -= listener
    }

    suspend fun pair(context: Context, host: String, port: Int, code: String): Boolean = withContext(Dispatchers.IO) {
        val normalizedCode = AdbPairingCode.normalize(code)
            ?: throw IllegalArgumentException("El código de emparejamiento debe tener seis dígitos.")
        val paired = AdbConnectionManager.getInstance(context).pair(host, port, normalizedCode)
        if (paired) {
            AppPreferences(context).setAdbPaired(true)
            AppLogger.i(TAG, "Wireless debugging pairing completed")
        }
        paired
    }

    /** Connects to adbd, starts the detached shell-UID recorder, and returns its Binder. */
    suspend fun getShellService(context: Context): IShellService = withContext(Dispatchers.IO) {
        shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withContext it }
        if (!isPaired(context)) {
            throw IllegalStateException("Esta instalación aún no está emparejada con la depuración inalámbrica de Android.")
        }

        launchMutex.withLock {
            shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            val adb = AdbConnectionManager.getInstance(context.applicationContext)
            ensureWirelessDebuggingEnabled(context)
            if (!adb.isConnected) {
                val endpoint = AdbMdns.discoverEndpoint(context, AdbMdns.TLS_CONNECT, CONNECT_DISCOVERY_TIMEOUT_MS)
                    ?: throw IOException("No se encontró el servicio de depuración inalámbrica. Activa Depuración inalámbrica, conecta Wi-Fi y vuelve a intentarlo.")
                runCatching { adb.connect(endpoint.host, endpoint.port) }
                    .onFailure {
                        if (it is io.github.muntashirakon.adb.AdbPairingRequiredException) {
                            AppPreferences(context).setAdbPaired(false)
                            throw IOException("Android ya no reconoce esta identidad ADB. Vuelve a emparejar desde el asistente.", it)
                        }
                        throw IOException("No se pudo conectar al servicio ADB ${endpoint.host}:${endpoint.port}: ${it.localizedMessage}", it)
                    }
            }
            grantSecureSettingsIfNeeded(context, adb)

            shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            killStaleDaemons(adb)
            launchDaemon(context, adb)

            val deadline = System.currentTimeMillis() + BINDER_WAIT_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
                delay(150)
            }
            throw IOException("ADB conectó, pero el servicio de grabación no entregó su Binder. Reinicia la depuración inalámbrica y vuelve a intentarlo.")
        }
    }

    /** Called only by the exported provider after it verifies that the sender is the shell UID. */
    fun onBinderReceived(context: Context, binder: IBinder) {
        val service = IShellService.Stub.asInterface(binder)
        if (service == null) {
            AppLogger.e(TAG, "ADB daemon delivered an invalid recorder Binder")
            return
        }
        linkedBinder?.let { old -> runCatching { old.unlinkToDeath(deathRecipient, 0) } }
        linkedBinder = binder
        shellService = service
        try {
            binder.linkToDeath(deathRecipient, 0)
            val prefs = AppPreferences(context)
            service.setLogCallback(AppLogger.callback, !prefs.isDebugEnabled())
            AppLogger.i(TAG, "Shell UID CAPTURE_AUDIO_OUTPUT permission: ${service.canCaptureAudioOutput()}")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not initialize the delivered recorder service", e)
        }
        AppLogger.i(TAG, "Shell-UID recorder Binder received by the app")
    }

    /** Allows the app to verify the shell permission used by the real-time capture engine. */
    suspend fun canCaptureAudioOutput(context: Context): Boolean =
        runCatching { getShellService(context).canCaptureAudioOutput() }.getOrDefault(false)

    private val deathRecipient = IBinder.DeathRecipient {
        shellService = null
        linkedBinder = null
        AppLogger.e(TAG, SERVICE_LOST_ERROR)
        serviceLostListeners.forEach { listener -> runCatching(listener) }
    }

    private fun ensureWirelessDebuggingEnabled(context: Context) {
        if (runCatching { Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1 }.getOrDefault(false)) return
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            throw IOException("La depuración inalámbrica está apagada. Actívala en Opciones de desarrollador y vuelve a emparejar; Android no permite reactivarla automáticamente en este dispositivo.")
        }
        if (!Settings.Global.putInt(context.contentResolver, "adb_wifi_enabled", 1)) {
            throw IOException("Android rechazó la reactivación de la depuración inalámbrica.")
        }
        Thread.sleep(750)
    }

    private fun grantSecureSettingsIfNeeded(context: Context, adb: AdbConnectionManager) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val command = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS; echo GRABACIONES_GRANT:$?"
            val result = adb.openStream("shell:$command").use { stream ->
                stream.openInputStream().bufferedReader().use { it.readText() }
            }
            AppLogger.i(TAG, "WRITE_SECURE_SETTINGS shell grant result: ${result.trim()}")
        }.onFailure { AppLogger.w(TAG, "Could not grant WRITE_SECURE_SETTINGS; reboot recovery may need manual Wireless debugging", it) }
    }

    private fun killStaleDaemons(adb: AdbConnectionManager) {
        runCatching {
            val command = "p=\$(pgrep -f 'org.jcastillo.grabaciones.services.daemon.Embedded[R]ecorderDaemon' 2>/dev/null); [ -n \"\$p\" ] && kill \$p 2>/dev/null; true"
            adb.openStream("shell:$command").use { it.openInputStream().bufferedReader().use { reader -> reader.readText() } }
        }.onFailure { AppLogger.w(TAG, "Could not clear an earlier recorder daemon; continuing with a fresh launch", it) }
    }

    private fun launchDaemon(context: Context, adb: AdbConnectionManager) {
        val apkPath = context.applicationInfo.sourceDir
        val authority = EmbeddedAdbBinderProvider.AUTHORITY
        val command = "setsid sh -c 'CLASSPATH=$apkPath exec app_process / $DAEMON_CLASS $authority' >/dev/null 2>&1 </dev/null & sleep 3"
        runCatching {
            adb.openStream("shell:$command").use { it.openInputStream().bufferedReader().use { reader -> reader.readText() } }
        }.onFailure { AppLogger.w(TAG, "ADB launch stream closed; waiting for daemon Binder delivery", it) }
    }
}
