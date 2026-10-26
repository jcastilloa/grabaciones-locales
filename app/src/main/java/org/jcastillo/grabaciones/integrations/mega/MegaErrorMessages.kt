package org.jcastillo.grabaciones.integrations.mega

import nz.mega.sdk.MegaError

/** User-facing MEGA diagnostics, with SDK details retained but sensitive values redacted. */
internal object MegaErrorMessages {
    fun login(
        error: MegaSdkException,
        twoFactorAttempt: Boolean,
        sensitiveValues: List<String> = emptyList(),
    ): String {
        val code = error.errorCode
        val summary = when (error.operation) {
            MegaOperation.INITIALIZATION -> when (code) {
                MegaError.API_EAPPKEY -> "El SDK de MEGA rechazó la inicialización de la API (error de App Key)."
                else -> "No se pudo inicializar el SDK de MEGA."
            }
            MegaOperation.FETCH_NODES -> "La sesión se aceptó, pero MEGA no pudo cargar los archivos de la cuenta."
            MegaOperation.SESSION_RESTORE -> "No se pudo restaurar la sesión guardada de MEGA. Vuelve a conectar la cuenta."
            MegaOperation.SESSION_PERSISTENCE -> "MEGA inició sesión, pero no se pudo guardar la sesión cifrada en este dispositivo."
            MegaOperation.LOGIN -> when {
                code == MegaError.API_EMFAREQUIRED ->
                    "Esta cuenta requiere verificación en dos pasos. Escribe el código actual de tu aplicación autenticadora."
                twoFactorAttempt && code != null && code in setOf(MegaError.API_ENOENT, MegaError.API_EACCESS) ->
                    "El código 2FA no es válido o venció. Comprueba tu aplicación autenticadora e inténtalo de nuevo."
                code != null && code in setOf(MegaError.API_ENOENT, MegaError.API_EACCESS) ->
                    "No se pudo iniciar sesión. Comprueba el correo y la contraseña."
                code == MegaError.API_EAPPKEY -> "MEGA rechazó la solicitud de autenticación por un error de configuración de la API."
                code == MegaError.API_EBLOCKED -> "MEGA ha bloqueado temporalmente esta cuenta. Revisa su estado."
                code != null && code in CONNECTION_ERRORS -> "No se pudo conectar con MEGA. Comprueba la red e inténtalo de nuevo."
                code != null && code in RATE_LIMIT_ERRORS -> "MEGA limitó temporalmente las solicitudes. Espera un momento e inténtalo de nuevo."
                code != null && code in INVALID_SESSION_ERRORS -> "La sesión de MEGA no es válida. Vuelve a conectar la cuenta."
                code != null && code in SERVER_ERRORS -> "MEGA no pudo completar el inicio de sesión por un error del servidor."
                else -> "No se pudo iniciar sesión con MEGA."
            }
            else -> "No se pudo ${error.operation.label} con MEGA."
        }
        return appendDiagnostic(summary, error, sensitiveValues)
    }

    fun backup(error: MegaSdkException): String {
        val code = error.errorCode
        val summary = when {
            code == MegaError.API_EAPPKEY -> "MEGA rechazó la solicitud por un error de configuración de la API."
            code != null && code in INVALID_SESSION_ERRORS -> "La sesión de MEGA caducó o dejó de ser válida. Vuelve a conectar la cuenta."
            code == MegaError.API_EMFAREQUIRED -> "MEGA requiere volver a verificar la cuenta con el código 2FA."
            code == MegaError.API_EOVERQUOTA || code == MegaError.API_EGOINGOVERQUOTA ->
                "La cuenta MEGA no tiene cuota disponible para completar el respaldo."
            code != null && code in RATE_LIMIT_ERRORS -> "MEGA limitó temporalmente las solicitudes; el respaldo se reintentará."
            code != null && code in CONNECTION_ERRORS -> "No se pudo conectar con MEGA durante la operación; se reintentará."
            code == MegaError.API_EBLOCKED -> "MEGA bloqueó temporalmente la cuenta. Revisa su estado."
            code != null && code in SERVER_ERRORS -> "MEGA devolvió un error del servidor durante la operación; se reintentará."
            error.operation == MegaOperation.FETCH_NODES ->
                "No se pudieron cargar los archivos de la cuenta MEGA; la grabación sigue pendiente."
            error.operation == MegaOperation.INITIALIZATION -> "No se pudo inicializar el SDK de MEGA."
            else -> "No se pudo ${error.operation.label} con MEGA; la grabación se conserva y se reintentará."
        }
        return appendDiagnostic(summary, error)
    }

    fun unexpectedLogin(error: Exception, sensitiveValues: List<String> = emptyList()): String {
        val safe = redact(error.message, sensitiveValues)
        return when {
            safe?.contains("App Key", ignoreCase = true) == true ->
                "MEGA rechazó la configuración de la solicitud a la API."
            safe?.contains("no respondió", ignoreCase = true) == true ->
                "MEGA tardó demasiado en responder. Comprueba la conexión e inténtalo de nuevo."
            safe?.contains("sesión recuperable", ignoreCase = true) == true ->
                "MEGA inició sesión, pero no devolvió una sesión que se pueda guardar. Inténtalo de nuevo."
            else -> "No se pudo conectar con MEGA. Comprueba los datos y la conexión e inténtalo de nuevo."
        }
    }

    private fun appendDiagnostic(
        summary: String,
        error: MegaSdkException,
        sensitiveValues: List<String> = emptyList(),
    ): String {
        val details = buildList {
            error.errorCode?.let { add("código $it") }
            redact(error.sdkMessage, sensitiveValues)?.let { add("mensaje del SDK: $it") }
        }
        return if (details.isEmpty()) summary else "$summary (${details.joinToString("; ")})"
    }

    private fun redact(message: String?, sensitiveValues: List<String>): String? {
        var safe = message?.trim()?.takeIf(String::isNotBlank) ?: return null
        sensitiveValues.asSequence()
            .filter(String::isNotEmpty)
            .distinct()
            .forEach { secret -> safe = safe.replace(secret, "[oculto]") }
        safe = SECRET_ASSIGNMENT.replace(safe) { "${it.groupValues[1]}=[oculto]" }
        safe = BEARER_TOKEN.replace(safe, "Bearer [oculto]")
        return safe.take(MAX_SDK_MESSAGE_LENGTH)
    }

    private val CONNECTION_ERRORS = setOf(
        MegaError.API_EAGAIN,
        MegaError.API_ETEMPUNAVAIL,
        MegaError.API_ETOOMANYCONNECTIONS,
        MegaError.API_ESSL,
        MegaError.LOCAL_ENETWORK,
        MegaError.LOCAL_ETIMEOUT,
    )
    private val RATE_LIMIT_ERRORS = setOf(
        MegaError.API_ERATELIMIT,
        MegaError.API_ETOOMANY,
        MegaError.API_ETOOMANYCONNECTIONS,
    )
    private val INVALID_SESSION_ERRORS = setOf(
        MegaError.API_ESID,
        MegaError.API_EKEY,
        MegaError.API_EEXPIRED,
    )
    private val SERVER_ERRORS = setOf(
        MegaError.API_EINTERNAL,
        MegaError.API_EFAILED,
        MegaError.API_EROLLEDBACK,
    )
    private val SECRET_ASSIGNMENT = Regex(
        "(?i)\\b(password|passwd|passphrase|session(?:[_ -]?token)?|sid|token|authorization|(?:app[_ -]?)?key)\\b\\s*[:=]\\s*(\"[^\"]*\"|'[^']*'|[^\\s,;]+)",
    )
    private val BEARER_TOKEN = Regex("(?i)\\bBearer\\s+\\S+")
    private const val MAX_SDK_MESSAGE_LENGTH = 320
}
