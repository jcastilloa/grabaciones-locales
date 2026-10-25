/*
 * Grabaciones Locales: in-process state for the ADB pairing foreground service and its UI.
 * Adapted from the pairing flow in CallVault; see THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.integrations.adb

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

enum class AdbPairingResult { NONE, SUCCESS, FAILURE, CANCELLED }

data class AdbPairingUiState(
    val sessionId: Long = 0,
    val active: Boolean = false,
    val notificationReady: Boolean = false,
    val waitingForWirelessDebugging: Boolean = false,
    val searching: Boolean = false,
    val pairingHost: String? = null,
    val pairingPort: Int? = null,
    val pairingInProgress: Boolean = false,
    val result: AdbPairingResult = AdbPairingResult.NONE,
    val message: String? = null,
    val isError: Boolean = false,
)

/** Shared state is safe because Android runs the service in the app process by default. */
object AdbPairingSession {
    private val nextId = AtomicLong()
    private val _state = MutableStateFlow(AdbPairingUiState())
    val state = _state.asStateFlow()

    fun begin(): Long {
        val sessionId = nextId.incrementAndGet()
        _state.value = AdbPairingUiState(
            sessionId = sessionId,
            active = true,
            searching = true,
            message = "Preparando la notificación de emparejamiento…",
        )
        return sessionId
    }

    fun notificationPublished() = _state.update {
        it.copy(notificationReady = true)
    }

    fun waitingForWirelessDebugging() = _state.update {
        it.copy(
            active = true,
            waitingForWirelessDebugging = true,
            searching = false,
            pairingHost = null,
            pairingPort = null,
            message = "Activa Depuración inalámbrica. La búsqueda comenzará automáticamente.",
            isError = false,
        )
    }

    fun searching() = _state.update {
        it.copy(
            active = true,
            waitingForWirelessDebugging = false,
            searching = true,
            pairingHost = null,
            pairingPort = null,
            pairingInProgress = false,
            result = AdbPairingResult.NONE,
            message = "Buscando el servicio de Android. El código se introduce desde la notificación, sin volver a esta pantalla.",
            isError = false,
        )
    }

    fun serviceFound(host: String, port: Int) = _state.update {
        it.copy(
            active = true,
            waitingForWirelessDebugging = false,
            searching = false,
            pairingHost = host,
            pairingPort = port,
            pairingInProgress = it.pairingInProgress,
            result = if (it.pairingInProgress) it.result else AdbPairingResult.NONE,
            message = "Servicio encontrado. Despliega las notificaciones y pulsa «Introducir código».",
            isError = false,
        )
    }

    fun serviceLost() = _state.update {
        if (!it.active || it.pairingInProgress) it
        else it.copy(
            pairingHost = null,
            pairingPort = null,
            searching = true,
            message = "El servicio dejó de anunciarse. Abre de nuevo «Vincular dispositivo con código».",
            isError = true,
        )
    }

    fun discoveryFailed(message: String) = _state.update {
        it.copy(
            active = false,
            waitingForWirelessDebugging = false,
            searching = false,
            pairingHost = null,
            pairingPort = null,
            pairingInProgress = false,
            result = AdbPairingResult.FAILURE,
            message = message,
            isError = true,
        )
    }

    fun pairingStarted() = _state.update {
        it.copy(pairingInProgress = true, result = AdbPairingResult.NONE, message = "Emparejando con Android…", isError = false)
    }

    fun pairingFailed(message: String) = _state.update {
        it.copy(
            active = true,
            pairingInProgress = false,
            result = AdbPairingResult.FAILURE,
            message = message,
            isError = true,
        )
    }

    fun pairingSucceeded(message: String) = _state.update {
        it.copy(
            active = false,
            waitingForWirelessDebugging = false,
            searching = false,
            pairingInProgress = false,
            result = AdbPairingResult.SUCCESS,
            message = message,
            isError = false,
        )
    }

    fun updateSuccessMessage(message: String) = _state.update {
        if (it.result == AdbPairingResult.SUCCESS) it.copy(message = message, isError = false) else it
    }

    fun cancelled() = _state.update {
        it.copy(
            active = false,
            waitingForWirelessDebugging = false,
            searching = false,
            pairingInProgress = false,
            result = AdbPairingResult.CANCELLED,
            message = "Búsqueda de emparejamiento cancelada.",
            isError = false,
        )
    }

    fun showError(message: String) = _state.update { it.copy(message = message, isError = true) }
}

/** ADB pairing codes are six ASCII decimal digits, not arbitrary Unicode numerals. */
object AdbPairingCode {
    fun normalize(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.any { it !in '0'..'9' && it != ' ' && it != '-' }) return null
        return trimmed.filter { it in '0'..'9' }.takeIf { it.length == 6 }
    }
}
