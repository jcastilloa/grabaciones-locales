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
import io.github.muntashirakon.adb.AdbStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns the persisted wireless-ADB identity, shell connection and recorder Binder lifecycle. */
object EmbeddedAdbManager {
    private const val TAG = "GL:EmbeddedAdb"
    private const val CONNECT_DISCOVERY_TIMEOUT_MS = 12_000L
    private const val CONNECT_HANDSHAKE_TIMEOUT_MS = 8_000L
    private const val BINDER_WAIT_TIMEOUT_MS = 18_000L
    private const val LOOPBACK_RECOVERY_TIMEOUT_MS = 12_000L
    private const val LOOPBACK_RETRY_INTERVAL_MS = 750L
    private const val ADB_READ_TIMEOUT_MS = 2_000L
    private const val DISCONNECT_TIMEOUT_MS = 2_000L
    private const val LAUNCH_DRAIN_TIMEOUT_MS = 4_000L
    private const val DAEMON_CLASS = "org.jcastillo.grabaciones.services.daemon.EmbeddedRecorderDaemon"
    private const val SERVICE_LOST_ERROR = "Se perdió el servicio ADB integrado. Comprueba la depuración inalámbrica y vuelve a emparejar si hace falta."

    private val launchMutex = Mutex()
    private val binderLock = Any()
    private val serviceLostListeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile
    private var captureInProgress = false

    @Volatile
    private var shellService: IShellService? = null

    @Volatile
    private var linkedBinder: IBinder? = null

    @Volatile
    private var linkedDeathRecipient: IBinder.DeathRecipient? = null

    fun isPaired(context: Context): Boolean = AppPreferences(context).isAdbPaired()

    fun isRecorderConnected(): Boolean = shellService?.asBinder()?.isBinderAlive == true

    /** Settings must not restart adbd while a remote capture is active or being started. */
    fun markCaptureStarting() {
        captureInProgress = true
    }

    fun markCaptureFinished() {
        captureInProgress = false
    }

    fun isCaptureInProgress(): Boolean = captureInProgress

    private fun checkTransportChangeAllowed() {
        if (captureInProgress) {
            throw IllegalStateException("No se puede cambiar el transporte ADB mientras una grabación está iniciándose o activa.")
        }
    }

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
     * classic-tcpip listener once over Wireless debugging. Later calls dial it through 127.0.0.1
     * and never run mDNS discovery, so losing Wi-Fi is not treated as losing the recorder. The
     * tcpip listener may still be published on non-loopback interfaces by adbd.
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
                        throw IOException("No se pudo preparar el transporte tcpip sin Wi-Fi. Mantén Wi-Fi conectado y vuelve a intentarlo.")
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
        checkTransportChangeAllowed()
        val appContext = context.applicationContext
        val prefs = AppPreferences(appContext)
        prefs.setOfflineRecordingEnabled(true)
        try {
            val alreadyWarm = shellService?.asBinder()?.isBinderAlive == true
            var needsRecovery = !alreadyWarm
            if (alreadyWarm) {
                launchMutex.withLock {
                    if (shellService?.asBinder()?.isBinderAlive == true) {
                        if (!isWifiConnected(appContext)) {
                            throw IOException("Para preparar el transporte sin Wi-Fi necesitas Wi-Fi una sola vez; el servicio actual sigue operativo.")
                        }
                        val adb = AdbConnectionManager.getInstance(appContext)
                        if (!adb.isConnected) connectWireless(appContext, adb)
                        synchronized(binderLock) {
                            shellService = null
                            linkedBinder?.let { old -> runCatching { old.unlinkToDeath(linkedDeathRecipient ?: deathRecipient, 0) } }
                            linkedBinder = null
                            linkedDeathRecipient = null
                        }
                        armLoopback(appContext, adb)
                        if (!connectLoopback(appContext, adb)) {
                            throw IOException("Android no volvió a publicar el transporte tcpip después de reiniciar adbd.")
                        }
                        grantSecureSettingsIfNeeded(appContext, adb)
                        killStaleDaemons(adb)
                        launchDaemon(appContext, adb)
                        awaitShellService()
                        needsRecovery = false
                    } else {
                        needsRecovery = true
                    }
                }
            }
            if (needsRecovery) getShellService(appContext)
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
        checkTransportChangeAllowed()
        val appContext = context.applicationContext
        launchMutex.withLock {
            AppPreferences(appContext).setOfflineRecordingEnabled(false)
            runCatching {
                val ownPort = AppPreferences(appContext).getLoopbackAdbPort()
                val currentPort = adbdTcpPort()
                if (currentPort != null && currentPort != ownPort) {
                    AppLogger.i(TAG, "Not disarming tcpip port $currentPort because it belongs to another transport")
                    return@runCatching
                }
                val adb = AdbConnectionManager.getInstance(appContext)
                if (!adb.isConnected) adb.connect("127.0.0.1", ownPort)
                if (adb.isConnected) {
                    shellService?.let { runCatching { it.destroy() } }
                    synchronized(binderLock) {
                        shellService = null
                        linkedBinder?.let { old -> runCatching { old.unlinkToDeath(linkedDeathRecipient ?: deathRecipient, 0) } }
                        linkedBinder = null
                        linkedDeathRecipient = null
                    }
                    val worker = Thread {
                        runCatching { adb.openStream("usb:").close() }
                            .onFailure { AppLogger.d(TAG, "tcpip disarm ended while adbd restarted: ${it.localizedMessage}") }
                    }.apply { isDaemon = true; name = "grabaciones-adb-disarm-loopback" }
                    worker.start()
                    worker.join(3_000L)
                    disconnectBounded(adb)
                }
            }.onFailure { AppLogger.w(TAG, "Could not close the off-Wi-Fi transport; it will reset on reboot", it) }
            AppLogger.i(TAG, "Off-Wi-Fi transport disabled")
        }
    }

    /** Wireless-debugging bootstrap. This is deliberately absent from the hot path when the Binder is alive. */
    private fun connectWireless(context: Context, adb: AdbConnectionManager): Boolean {
        ensureWirelessDebuggingEnabled(context)
        if (adb.isConnected) return true
        val endpoint = AdbMdns.discoverEndpoint(context, AdbMdns.TLS_CONNECT, CONNECT_DISCOVERY_TIMEOUT_MS)
            ?: throw IOException("No se encontró el servicio de depuración inalámbrica durante la inicialización. Comprueba Wi-Fi y Depuración inalámbrica.")
        val failure = AtomicReference<Throwable?>(null)
        val connected = AtomicBoolean(false)
        val worker = Thread {
            runCatching {
                adb.connect(endpoint.host, endpoint.port)
                connected.set(adb.isConnected)
            }.onFailure { failure.set(it) }
        }.apply { isDaemon = true; name = "grabaciones-adb-connect-wireless" }
        worker.start()
        worker.join(CONNECT_HANDSHAKE_TIMEOUT_MS)
        if (worker.isAlive) {
            worker.interrupt()
            disconnectBounded(adb)
            throw IOException("El handshake ADB inalámbrico superó ${CONNECT_HANDSHAKE_TIMEOUT_MS} ms.")
        }
        failure.get()?.let { error ->
            if (error is io.github.muntashirakon.adb.AdbPairingRequiredException) {
                AppPreferences(context).setAdbPaired(false)
                throw IOException("Android ya no reconoce esta identidad ADB. Vuelve a emparejar desde el asistente.", error)
            }
            throw IOException("No se pudo conectar al servicio ADB ${endpoint.host}:${endpoint.port}: ${error.localizedMessage}", error)
        }
        return connected.get()
    }

    /** Connects to classic tcpip on loopback; no Wi-Fi or Internet is involved. */
    private fun connectLoopback(context: Context, adb: AdbConnectionManager): Boolean {
        val port = AppPreferences(context).getLoopbackAdbPort()
        val configured = adbdTcpPort() == port
        val deadline = System.currentTimeMillis() +
            if (configured) LOOPBACK_RECOVERY_TIMEOUT_MS else LOOPBACK_RETRY_INTERVAL_MS
        do {
            if (adb.isConnected) disconnectBounded(adb)
            val connected = runCatching {
                adb.connect("127.0.0.1", port)
                adb.isConnected
            }.onFailure { AppLogger.d(TAG, "Loopback tcpip :$port unavailable: ${it.localizedMessage}") }.getOrDefault(false)
            if (connected) {
                AppLogger.i(TAG, "Connected to adbd over loopback :$port")
                return true
            }
            if (System.currentTimeMillis() < deadline) Thread.sleep(LOOPBACK_RETRY_INTERVAL_MS)
        } while (System.currentTimeMillis() < deadline)
        return false
    }

    private fun disconnectBounded(adb: AdbConnectionManager) {
        val worker = Thread { runCatching { adb.disconnect() } }
            .apply { isDaemon = true; name = "grabaciones-adb-disconnect" }
        worker.start()
        runCatching { worker.join(DISCONNECT_TIMEOUT_MS) }
        if (worker.isAlive) AppLogger.w(TAG, "ADB disconnect is still blocked after ${DISCONNECT_TIMEOUT_MS} ms")
    }

    private fun adbdTcpPort(): Int? = runCatching {
        Class.forName("android.os.SystemProperties")
            .getMethod("get", String::class.java)
            .invoke(null, "service.adb.tcp.port") as? String
    }.getOrNull()?.toIntOrNull()

    /**
     * Arms classic tcpip through the current Wireless-debugging connection. Opening tcpip
     * restarts adbd, therefore the operation is bounded, followed by an explicit disconnect
     * and a loopback reconnect. It is never called while recording.
     */
    private fun armLoopback(context: Context, adb: AdbConnectionManager): Boolean {
        val port = AppPreferences(context).getLoopbackAdbPort()
        val existingPort = adbdTcpPort()
        if (existingPort != null && existingPort != port) {
            throw IOException("adbd ya está en modo tcpip en el puerto $existingPort; no se reiniciará para reemplazar el transporte de otra aplicación.")
        }
        AppLogger.i(TAG, "Arming off-Wi-Fi tcpip transport on :$port")
        val worker = Thread {
            runCatching { adb.openStream("tcpip:$port").close() }
                .onFailure { AppLogger.d(TAG, "tcpip arm ended while adbd restarted: ${it.localizedMessage}") }
        }.apply { isDaemon = true; name = "grabaciones-adb-arm-loopback" }
        worker.start()
        runCatching { worker.join(3_000L) }
        disconnectBounded(adb)
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
        val recipient = IBinder.DeathRecipient {
            var wasCurrent = false
            synchronized(binderLock) {
                // A delayed death callback from an older daemon must not clear a newer Binder.
                if (linkedBinder === binder) {
                    shellService = null
                    linkedBinder = null
                    linkedDeathRecipient = null
                    wasCurrent = true
                }
            }
            if (wasCurrent) {
                AppLogger.e(TAG, SERVICE_LOST_ERROR)
                serviceLostListeners.forEach { listener -> runCatching(listener) }
            }
        }
        synchronized(binderLock) {
            linkedBinder?.let { old ->
                runCatching { old.unlinkToDeath(linkedDeathRecipient ?: deathRecipient, 0) }
            }
            linkedBinder = binder
            linkedDeathRecipient = recipient
            shellService = service
            try {
                binder.linkToDeath(recipient, 0)
            } catch (e: Exception) {
                AppLogger.e(TAG, "Could not link the delivered recorder Binder to death", e)
            }
        }
        try {
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

    // Retained only for compatibility with a Binder that was installed before the identity-aware
    // recipient was introduced; new deliveries always use a recipient bound to their Binder.
    private val deathRecipient = IBinder.DeathRecipient {
        synchronized(binderLock) {
            shellService = null
            linkedBinder = null
            linkedDeathRecipient = null
        }
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

    /** Drains an ADB stream without allowing a half-dead adbd connection to block forever. */
    private fun readAdbStreamBounded(stream: AdbStream, timeoutMs: Long): String {
        val result = AtomicReference("")
        val worker = Thread {
            runCatching {
                stream.openInputStream().bufferedReader().use { result.set(it.readText()) }
            }
        }.apply { isDaemon = true; name = "grabaciones-adb-read" }
        worker.start()
        worker.join(timeoutMs)
        if (worker.isAlive) {
            runCatching { stream.close() }
            worker.interrupt()
            AppLogger.w(TAG, "ADB stream read exceeded ${timeoutMs} ms; continuing without its output")
        }
        return result.get()
    }

    private fun grantSecureSettingsIfNeeded(context: Context, adb: AdbConnectionManager) {
        if (ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED) return
        runCatching {
            val command = "pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS; echo GRABACIONES_GRANT:$?"
            val result = adb.openStream("shell:$command").use { stream ->
                readAdbStreamBounded(stream, ADB_READ_TIMEOUT_MS)
            }
            AppLogger.i(TAG, "WRITE_SECURE_SETTINGS shell grant result: ${result.trim()}")
        }.onFailure { AppLogger.w(TAG, "Could not grant WRITE_SECURE_SETTINGS; reboot recovery may need manual Wireless debugging", it) }
    }

    private fun killStaleDaemons(adb: AdbConnectionManager) {
        runCatching {
            val command = "p=\$(pgrep -f 'org.jcastillo.grabaciones.services.daemon.Embedded[R]ecorderDaemon' 2>/dev/null); [ -n \"\$p\" ] && kill \$p 2>/dev/null; true"
            adb.openStream("shell:$command").use { readAdbStreamBounded(it, ADB_READ_TIMEOUT_MS) }
        }.onFailure { AppLogger.w(TAG, "Could not clear an earlier recorder daemon; continuing with a fresh launch", it) }
    }

    private fun launchDaemon(context: Context, adb: AdbConnectionManager) {
        val apkPath = context.applicationInfo.sourceDir
        val authority = EmbeddedAdbBinderProvider.AUTHORITY
        val command = "setsid sh -c 'CLASSPATH=$apkPath exec app_process / $DAEMON_CLASS $authority' >/dev/null 2>&1 </dev/null & sleep 3"
        // Keep the parent shell alive long enough for app_process to detach, but do not make the
        // recording path wait the full sleep once the daemon has delivered its Binder.
        Thread {
            runCatching {
                adb.openStream("shell:$command").use { readAdbStreamBounded(it, LAUNCH_DRAIN_TIMEOUT_MS) }
            }.onFailure { AppLogger.w(TAG, "ADB launch stream closed; waiting for daemon Binder delivery", it) }
        }.apply { isDaemon = true; name = "grabaciones-daemon-launch-drain" }.start()
    }
}
