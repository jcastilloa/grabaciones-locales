/*
 * AdbMdns — discovery for Android Wireless debugging services.
 *
 * Ported from RikkaApps/Shizuku (Apache-2.0):
 *   manager/src/main/java/moe/shizuku/manager/adb/AdbMdns.kt
 * The service-type names and DNS-SD resolution follow that implementation. Unlike the earlier
 * fork, this reports the resolved local address as well as the port: some OEM adbd instances do
 * not listen on 127.0.0.1, so the advertised endpoint must be used for pairing and reconnects.
 */
package org.jcastillo.grabaciones.integrations.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import org.jcastillo.grabaciones.utils.AppLogger
import java.net.InetAddress
import java.net.NetworkInterface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class AdbEndpoint(val host: String, val port: Int)

/** Keeps mDNS discovery alive until the user submits the code or cancels pairing. */
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val onEndpoint: (AdbEndpoint?) -> Unit,
    private val onError: (String) -> Unit = {},
) {
    @Volatile private var running = false
    @Volatile private var serviceName: String? = null
    private val listener = DiscoveryListener(this)
    private val nsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        running = true
        AppLogger.i(TAG, "Starting mDNS discovery for $serviceType")
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: Exception) {
            running = false
            val message = "No se pudo iniciar la búsqueda mDNS de $serviceType: ${e.localizedMessage ?: e.javaClass.simpleName}"
            AppLogger.e(TAG, message, e)
            onError(message)
        }
    }

    fun stop() {
        if (!running) return
        running = false
        // stopServiceDiscovery is safe to attempt even if onDiscoveryStarted has not arrived yet.
        runCatching { nsdManager.stopServiceDiscovery(listener) }
            .onFailure { AppLogger.w(TAG, "Stopping mDNS discovery failed: ${it.message}") }
    }

    private fun onDiscoveryStart() {
        if (!running) runCatching { nsdManager.stopServiceDiscovery(listener) }
    }

    private fun onDiscoveryStop() = Unit

    private fun onServiceFound(info: NsdServiceInfo) {
        if (!running) return
        AppLogger.d(TAG, "Found ${info.serviceName} ($serviceType); resolving")
        try {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(info, ResolveListener(this))
        } catch (e: Exception) {
            AppLogger.w(TAG, "Could not request resolution for ${info.serviceName}: ${e.message}")
        }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) {
            AppLogger.w(TAG, "Lost local mDNS service ${info.serviceName}")
            serviceName = null
            onEndpoint(null)
        }
    }

    @Suppress("DEPRECATION")
    private fun onServiceResolved(info: NsdServiceInfo) {
        if (!running) return
        val host = info.host?.hostAddress
        val verdict = MdnsServiceVerdict.of(host) { isOneOfOurAddresses(host) }
        if (verdict != MdnsServiceVerdict.ACCEPTED) {
            AppLogger.w(TAG, "Rejected ${info.serviceName} at ${host ?: "no address"}:${info.port} — $verdict")
            return
        }
        if (info.port !in 1..65535) {
            AppLogger.w(TAG, "Rejected ${info.serviceName}: invalid port ${info.port}")
            return
        }
        val endpoint = AdbEndpoint(requireNotNull(host), info.port)
        serviceName = info.serviceName
        AppLogger.i(TAG, "Accepted local ${info.serviceName} at ${endpoint.host}:${endpoint.port}")
        onEndpoint(endpoint)
    }

    private fun isOneOfOurAddresses(hostAddress: String?): Boolean = runCatching {
        if (hostAddress.isNullOrBlank()) return false
        val target = InetAddress.getByName(hostAddress)
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return false
        interfaces.toList().any { networkInterface ->
            networkInterface.inetAddresses.toList().any { address ->
                address.address.contentEquals(target.address)
            }
        }
    }.getOrElse {
        AppLogger.w(TAG, "Could not verify that $hostAddress belongs to this device: ${it.message}")
        false
    }

    private class DiscoveryListener(private val owner: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) = owner.onDiscoveryStart()
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            owner.running = false
            val message = "Android no pudo iniciar la búsqueda mDNS de emparejamiento (código $errorCode). Comprueba Wi-Fi y vuelve a intentarlo."
            AppLogger.e(TAG, message)
            owner.onError(message)
        }
        override fun onDiscoveryStopped(serviceType: String) = owner.onDiscoveryStop()
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            AppLogger.w(TAG, "mDNS stop failed for $serviceType (error $errorCode)")
        }
        override fun onServiceFound(serviceInfo: NsdServiceInfo) = owner.onServiceFound(serviceInfo)
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = owner.onServiceLost(serviceInfo)
    }

    @Suppress("DEPRECATION")
    private class ResolveListener(private val owner: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            AppLogger.w(TAG, "Could not resolve ${serviceInfo.serviceName} (error $errorCode)")
        }
        override fun onServiceResolved(serviceInfo: NsdServiceInfo) = owner.onServiceResolved(serviceInfo)
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        private const val TAG = "GL:AdbMdns"

        /** Blocks off the main thread until a local service is resolved or [timeoutMs] elapses. */
        fun discoverEndpoint(context: Context, serviceType: String, timeoutMs: Long): AdbEndpoint? {
            val result = AtomicReference<AdbEndpoint?>(null)
            val latch = CountDownLatch(1)
            val mdns = AdbMdns(
                context = context,
                serviceType = serviceType,
                onEndpoint = { endpoint ->
                    if (endpoint != null) {
                        result.set(endpoint)
                        latch.countDown()
                    }
                },
            )
            mdns.start()
            return try {
                if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                    AppLogger.w(TAG, "No local $serviceType service found within ${timeoutMs}ms")
                    null
                } else result.get()
            } finally {
                mdns.stop()
            }
        }
    }
}
