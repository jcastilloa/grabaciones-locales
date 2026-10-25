/*
 * Foreground service for integrated Android Wireless debugging pairing.
 * The notification/RemoteInput flow is adapted from CallVault under GPL-3.0-or-later;
 * see THIRD_PARTY_NOTICES.md and LICENSE.
 */
package org.jcastillo.grabaciones.services.adb

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.database.ContentObserver
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.jcastillo.grabaciones.R
import org.jcastillo.grabaciones.integrations.adb.AdbEndpoint
import org.jcastillo.grabaciones.integrations.adb.AdbMdns
import org.jcastillo.grabaciones.integrations.adb.AdbPairingCode
import org.jcastillo.grabaciones.integrations.adb.AdbPairingNotifications
import org.jcastillo.grabaciones.integrations.adb.AdbPairingSession
import org.jcastillo.grabaciones.integrations.adb.EmbeddedAdbManager
import org.jcastillo.grabaciones.utils.AppLogger

class AdbPairingService : Service() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var discovery: AdbMdns? = null
    private var wirelessDebuggingObserver: ContentObserver? = null
    @Volatile private var pairingHandled = false
    private var stoppedIntentionally = false

    private val wirelessDebuggingTimeout = Runnable {
        if (wirelessDebuggingObserver != null) {
            stopWaitingForWirelessDebugging()
            failAndStop("Depuración inalámbrica no se activó. Vuelve a intentarlo desde Opciones de desarrollador.")
        }
    }

    override fun onCreate() {
        super.onCreate()
        AdbPairingNotifications.ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        if (action == AdbPairingNotifications.ACTION_STOP) {
            stoppedIntentionally = true
            stopWaitingForWirelessDebugging()
            stopDiscovery()
            AdbPairingSession.cancelled()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val initialNotification = if (action == AdbPairingNotifications.ACTION_REPLY) {
            AdbPairingNotifications.working(this)
        } else {
            AdbPairingNotifications.searching(this)
        }
        try {
            startForegroundWithType(initialNotification)
            AdbPairingSession.notificationPublished()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Could not publish the pairing notification", e)
            failAndStop("No se pudo mostrar la notificación de emparejamiento. Activa las notificaciones de Grabaciones Locales y vuelve a intentarlo.")
            return START_NOT_STICKY
        }

        when (action) {
            AdbPairingNotifications.ACTION_START -> beginPairingFlow()
            AdbPairingNotifications.ACTION_REPLY -> handleSubmission(
                host = intent.getStringExtra(AdbPairingNotifications.EXTRA_HOST),
                port = intent.getIntExtra(AdbPairingNotifications.EXTRA_PORT, -1),
                code = AdbPairingNotifications.pairingCodeFromReply(intent).orEmpty(),
            )
            else -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginPairingFlow() {
        stopWaitingForWirelessDebugging()
        stopDiscovery()
        pairingHandled = false
        if (AdbPairingSession.state.value.sessionId == 0L || !AdbPairingSession.state.value.active) {
            AdbPairingSession.begin()
        }
        if (isWirelessDebuggingEnabled()) {
            beginDiscovery()
        } else {
            waitForWirelessDebugging()
        }
    }

    private fun beginDiscovery() {
        if (discovery != null) return
        AdbPairingSession.searching()
        updateForegroundNotification(AdbPairingNotifications.searching(this))
        discovery = AdbMdns(
            context = applicationContext,
            serviceType = AdbMdns.TLS_PAIRING,
            onEndpoint = ::onPairingEndpoint,
            onError = { message -> mainHandler.post { failAndStop(message) } },
        ).also { it.start() }
    }

    private fun onPairingEndpoint(endpoint: AdbEndpoint?) {
        val current = AdbPairingSession.state.value
        if (!current.active || current.pairingInProgress) return
        if (endpoint == null) {
            AdbPairingSession.serviceLost()
            updateForegroundNotification(AdbPairingNotifications.searching(this))
            return
        }
        AdbPairingSession.serviceFound(endpoint.host, endpoint.port)
        notify(AdbPairingNotifications.found(this, endpoint.host, endpoint.port))
    }

    private fun waitForWirelessDebugging() {
        AdbPairingSession.waitingForWirelessDebugging()
        updateForegroundNotification(AdbPairingNotifications.waitingForWirelessDebugging(this))
        if (wirelessDebuggingObserver != null) return
        AppLogger.i(TAG, "Wireless debugging is off; waiting for adb_wifi_enabled")
        val observer = object : ContentObserver(mainHandler) {
            override fun onChange(selfChange: Boolean) {
                if (isWirelessDebuggingEnabled()) {
                    AppLogger.i(TAG, "Wireless debugging enabled; starting pairing mDNS discovery")
                    stopWaitingForWirelessDebugging()
                    beginDiscovery()
                }
            }
        }
        wirelessDebuggingObserver = observer
        runCatching {
            contentResolver.registerContentObserver(Settings.Global.getUriFor(ADB_WIFI_ENABLED), false, observer)
        }.onFailure {
            AppLogger.e(TAG, "Could not observe Wireless debugging state", it)
            wirelessDebuggingObserver = null
            failAndStop("No se pudo observar el estado de Depuración inalámbrica. Abre de nuevo la pantalla de emparejamiento.")
        }
        mainHandler.postDelayed(wirelessDebuggingTimeout, WIRELESS_DEBUGGING_WAIT_TIMEOUT_MS)
    }

    private fun stopWaitingForWirelessDebugging() {
        mainHandler.removeCallbacks(wirelessDebuggingTimeout)
        wirelessDebuggingObserver?.let { observer ->
            runCatching { contentResolver.unregisterContentObserver(observer) }
                .onFailure { AppLogger.w(TAG, "Unregister Wireless debugging observer failed: ${it.message}") }
        }
        wirelessDebuggingObserver = null
    }

    private fun handleSubmission(host: String?, port: Int, code: String) {
        val endpointHost = host?.takeIf { it.isNotBlank() }
        val normalizedCode = AdbPairingCode.normalize(code)
        if (endpointHost == null || port !in 1..65535) {
            AdbPairingSession.pairingFailed("No se encontró el servicio de emparejamiento. Abre «Vincular dispositivo con código» y espera a que aparezca el servicio.")
            return
        }
        if (normalizedCode == null) {
            val error = "El código debe contener seis cifras. Introdúcelo de nuevo."
            AdbPairingSession.pairingFailed(error)
            notify(AdbPairingNotifications.found(this, endpointHost, port, error))
            return
        }
        if (pairingHandled) return
        pairingHandled = true
        AdbPairingSession.pairingStarted()
        updateForegroundNotification(AdbPairingNotifications.working(this))

        serviceScope.launch {
            try {
                val paired = EmbeddedAdbManager.pair(applicationContext, endpointHost, port, normalizedCode)
                if (!paired) throw IllegalStateException("Android rechazó el código de emparejamiento.")
                stopWaitingForWirelessDebugging()
                stopDiscovery()
                val resultText = "Emparejamiento ADB completado. La conexión al servicio se comprobará cuando la app la necesite."
                AdbPairingSession.pairingSucceeded(resultText)
                notify(
                    AdbPairingNotifications.result(
                        this@AdbPairingService,
                        getString(R.string.notif_pairing_success_title),
                        resultText,
                        openApp = true,
                    ),
                )
                stopForeground(STOP_FOREGROUND_DETACH)
                stopSelf()
            } catch (e: Exception) {
                pairingHandled = false
                val error = e.localizedMessage ?: "No se pudo completar el emparejamiento."
                AppLogger.e(TAG, "ADB pairing failed at $endpointHost:$port", e)
                AdbPairingSession.pairingFailed(error)
                notify(AdbPairingNotifications.found(this@AdbPairingService, endpointHost, port, error))
                updateForegroundNotification(AdbPairingNotifications.found(this@AdbPairingService, endpointHost, port, error))
            }
        }
    }

    private fun failAndStop(message: String) {
        AppLogger.e(TAG, message)
        stopWaitingForWirelessDebugging()
        stopDiscovery()
        AdbPairingSession.discoveryFailed(message)
        notify(AdbPairingNotifications.result(this, getString(R.string.notif_pairing_failed_title), message, openApp = true))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun stopDiscovery() {
        discovery?.stop()
        discovery = null
    }

    private fun isWirelessDebuggingEnabled(): Boolean =
        runCatching { Settings.Global.getInt(contentResolver, ADB_WIFI_ENABLED, 0) == 1 }.getOrDefault(false)

    private fun notify(notification: android.app.Notification) {
        runCatching { getSystemService(NotificationManager::class.java).notify(AdbPairingNotifications.NOTIFICATION_ID, notification) }
            .onFailure { AppLogger.w(TAG, "Pairing notification could not be posted (check notification permission/channel)", it) }
    }

    private fun updateForegroundNotification(notification: android.app.Notification) {
        runCatching { startForegroundWithType(notification) }
            .onFailure { AppLogger.e(TAG, "Could not update pairing foreground notification", it) }
    }

    private fun startForegroundWithType(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                AdbPairingNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(AdbPairingNotifications.NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        stopWaitingForWirelessDebugging()
        stopDiscovery()
        if (!stoppedIntentionally && AdbPairingSession.state.value.active) {
            AdbPairingSession.discoveryFailed("El servicio de emparejamiento se cerró antes de terminar. Vuelve a intentarlo.")
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "GL:AdbPairingService"
        private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val WIRELESS_DEBUGGING_WAIT_TIMEOUT_MS = 5 * 60 * 1000L

        fun start(context: Context): Long {
            val sessionId = AdbPairingSession.begin()
            val intent = Intent(context, AdbPairingService::class.java).setAction(AdbPairingNotifications.ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                AdbPairingSession.discoveryFailed("No se pudo iniciar el servicio de emparejamiento: ${e.localizedMessage ?: e.javaClass.simpleName}")
                throw e
            }
            return sessionId
        }

        fun stop(context: Context) {
            val intent = Intent(context, AdbPairingService::class.java).setAction(AdbPairingNotifications.ACTION_STOP)
            runCatching { context.startService(intent) }
        }
    }
}
