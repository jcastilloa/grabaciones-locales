/*
 * Grabaciones Locales: embedded wireless-ADB integration adapted from CallVault.
 * CallVault source copyright: CallVault Authors; GPL-3.0-or-later. See LICENSE and THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.integrations.adb

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
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

    /**
     * Connects to the already-running privileged daemon when possible. ADB is only used to
     * bootstrap or recover that daemon; recording commands themselves use Binder.
     *
     * When off-Wi-Fi recording is enabled, the first setup arms CallVault's classic-tcpip
     * loopback listener once over Wireless debugging. Later calls use 127.0.0.1 and never run
     * mDNS discovery, so losing Wi-Fi is not treated as losing the recorder.
     */
    suspend fun getShellService(context: Context): IShellService = withContext(Dispatchers.IO) {
        shellService?.takeIf { it.asBinder().isBinderAlive }?.let {
            AppLogger.d(TAG, "Reusing live recorder Binder; no ADB/Wi-Fi check")
            return@withContext it
        }
        if (!isPaired(context)) {
            throw IllegalStateException("Esta instalación aún no está emparejada con la depuración inalámbrica de Android.")
        }

        launchMutex.withLock {
            shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            val appContext = context.applicationContext
            val adb = AdbConnectionManager.getInstance(appContext)
            val offline = AppPreferences(appContext).isOfflineRecordingEnabled()

            var connected = if (offline) connectLoopback(appContext, adb) else false
            if (!connected) {
                // Wireless debugging is a bootstrap/recovery transport, not a prerequisite for
                // an already delivered Binder. Do not even start mDNS when it cannot exist.
                if (!isWifiConnected(appContext)) {
                    throw IOException(
                        "El servicio privilegiado no está conectado y el transporte sin Wi-Fi no está preparado. " +
                            "Conecta Wi-Fi una vez para inicializarlo o activa Grabación sin Wi-Fi en Ajustes."
                    )
                }
                connected = connectWireless(appContext, adb)
                if (connected && offline) {
                    // tcpip: restarts adbd and invalidates this ADB connection. The daemon is
                    // launched only after reconnecting through loopback; otherwise the first
                    // listener restart could kill the just-launched daemon.
                    if (!armLoopback(appContext, adb) || !connectLoopback(appContext, adb)) {
                        throw IOException("No se pudo preparar el transporte local sin Wi-Fi. Mantén Wi-Fi conectado y vuelve a intentarlo.")
                    }
                }
            }
            if (!connected && !adb.isConnected) {
                throw IOException("No se pudo establecer un transporte ADB para recuperar el servicio privilegiado.")
            }
            grantSecureSettingsIfNeeded(appContext, adb)

            shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return@withLock it }
            killStaleDaemons(adb)
            launchDaemon(appContext, adb)

            return@withLock awaitShellService()
        }
    }

    private suspend fun awaitShellService(): IShellService {
        val deadline = System.currentTimeMillis() + BINDER_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            shellService?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
            delay(150)
        }
        throw IOException("El transporte ADB está conectado, pero el servicio privilegiado no entregó su Binder. Revisa el diagnóstico del servicio, no la conexión a Internet.")
    }

    /**
     * Enables the loopback transport and verifies that the privileged daemon can be recovered
     * through it. If a daemon was already warm over Wireless debugging, it is relaunched after
     * arming: `tcpip:` restarts adbd and must never be issued while relying on that old daemon.
     */
    suspend fun enableOfflineRecording(context: Context): Boolean = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        val prefs = AppPreferences(appContext)
        prefs.setOfflineRecordingEnabled(true)
        try {
            val alreadyWarm = shellService?.asBinder()?.isBinderAlive == true
            if (alreadyWarm) {
                launchMutex.withLock {
                    if (shellService?.asBinder()?.isBinderAlive == true) {
                        if (!isWifiConnected(appContext)) {
                            throw IOException("Para preparar el transporte sin Wi-Fi necesitas Wi-Fi una sola vez; el servicio actual sigue operativo.")
                        }
                        val adb = AdbConnectionManager.getInstance(appContext)
                        if (!adb.isConnected) connectWireless(appContext, adb)
                        shellService = null
                        linkedBinder?.let { old -> runCatching { old.unlinkToDeath(deathRecipient, 0) } }
                        linkedBinder = null
                        armLoopback(appContext, adb)
                        if (!connectLoopback(appContext, adb)) {
                            throw IOException("Android no volvió a publicar el transporte local después de reiniciar adbd.")
                        }
                        grantSecureSettingsIfNeeded(appContext, adb)
                        killStaleDaemons(adb)
                        launchDaemon(appContext, adb)
                        awaitShellService()
                    }
                }
            } else {
                getShellService(appContext)
            }
            true
        } catch (error: Throwable) {
            prefs.setOfflineRecordingEnabled(false)
            throw error
        }
    }

    /**
     * Disables the opt-in transport and best-effort reverts adbd to USB mode. This is an idle
     * settings operation, never part of a recording start. If Android refuses the restart, the
     * preference is still cleared and the port will disappear on the next reboot.
     */
    suspend fun disableOfflineRecording(context: Context) = withContext(Dispatchers.IO) {
        val appContext = context.applicationContext
        AppPreferences(appContext).setOfflineRecordingEnabled(false)
        runCatching {
            val adb = AdbConnectionManager.getInstance(appContext)
            if (!adb.isConnected) adb.connect("127.0.0.1", AppPreferences(appContext).getLoopbackAdbPort())
            if (adb.isConnected) {
                shellService?.let { runCatching { it.destroy() } }
                shellService = null
                val worker = Thread {
                    runCatching { adb.openStream("usb:").close() }
                        .onFailure { AppLogger.d(TAG, "tcpip disarm ended while adbd restarted: ${it.localizedMessage}") }
                }.apply { isDaemon = true; name = "grabaciones-adb-disarm-loopback" }
                worker.start()
                worker.join(3_000L)
                adb.disconnect()
            }
        }.onFailure { AppLogger.w(TAG, "Could not close the off-Wi-Fi transport; it will reset on reboot", it) }
        AppLogger.i(TAG, "Off-Wi-Fi transport disabled")
    }

    /** Wireless-debugging bootstrap. This is deliberately absent from the hot path when the Binder is alive. */
    private fun connectWireless(context: Context, adb: AdbConnectionManager): Boolean {
        ensureWirelessDebuggingEnabled(context)
        if (adb.isConnected) return true
        val endpoint = AdbMdns.discoverEndpoint(context, AdbMdns.TLS_CONNECT, CONNECT_DISCOVERY_TIMEOUT_MS)
            ?: throw IOException("No se encontró el servicio de depuración inalámbrica durante la inicialización. Comprueba Wi-Fi y Depuración inalámbrica.")
        return runCatching {
            adb.connect(endpoint.host, endpoint.port)
            true
        }.onFailure {
            if (it is io.github.muntashirakon.adb.AdbPairingRequiredException) {
                AppPreferences(context).setAdbPaired(false)
                throw IOException("Android ya no reconoce esta identidad ADB. Vuelve a emparejar desde el asistente.", it)
            }
        }.getOrElse { throw IOException("No se pudo conectar al servicio ADB ${endpoint.host}:${endpoint.port}: ${it.localizedMessage}", it) }
    }

    /** Connects to classic tcpip on loopback; no Wi-Fi or Internet is involved. */
    private fun connectLoopback(context: Context, adb: AdbConnectionManager): Boolean {
        // The manager does not expose the current endpoint. Drop a possibly stale wireless
        // connection before attempting loopback; this is safe because no Binder is alive here.
        if (adb.isConnected) runCatching { adb.disconnect() }
        val port = AppPreferences(context).getLoopbackAdbPort()
        return runCatching {
            adb.connect("127.0.0.1", port)
            adb.isConnected
        }.onFailure { AppLogger.d(TAG, "Loopback tcpip :$port unavailable: ${it.localizedMessage}") }.getOrDefault(false)
            .also { if (it) AppLogger.i(TAG, "Connected to adbd over loopback :$port") }
    }

    /**
     * Arms classic tcpip through the current Wireless-debugging connection. Opening tcpip
     * restarts adbd, therefore the operation is bounded, followed by an explicit disconnect
     * and a loopback reconnect. It is never called while recording.
     */
    private fun armLoopback(context: Context, adb: AdbConnectionManager): Boolean {
        val port = AppPreferences(context).getLoopbackAdbPort()
        AppLogger.i(TAG, "Arming off-Wi-Fi loopback transport on :$port")
        val worker = Thread {
            runCatching { adb.openStream("tcpip:$port").close() }
                .onFailure { AppLogger.d(TAG, "tcpip arm ended while adbd restarted: ${it.localizedMessage}") }
        }.apply { isDaemon = true; name = "grabaciones-adb-arm-loopback" }
        worker.start()
        runCatching { worker.join(3_000L) }
        runCatching { adb.disconnect() }
        Thread.sleep(1_500L)
        return true
    }

    private fun isWifiConnected(context: Context): Boolean = runCatching {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }.getOrDefault(false)

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
