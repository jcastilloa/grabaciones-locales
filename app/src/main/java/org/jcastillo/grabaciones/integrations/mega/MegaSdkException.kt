package org.jcastillo.grabaciones.integrations.mega

internal enum class MegaOperation(val label: String) {
    INITIALIZATION("inicializar el SDK"),
    LOGIN("iniciar sesión"),
    SESSION_PERSISTENCE("guardar la sesión"),
    SESSION_RESTORE("restaurar la sesión"),
    LOGOUT("cerrar sesión"),
    FETCH_NODES("cargar los archivos de la cuenta"),
    CREATE_FOLDER("preparar la carpeta de respaldo"),
    UPLOAD("subir la grabación"),
    OTHER("completar la operación"),
}

/** Retains the original MEGA result while keeping it out of exception/log text. */
internal class MegaSdkException(
    val operation: MegaOperation,
    val errorCode: Int? = null,
    val sdkMessage: String? = null,
    cause: Throwable? = null,
) : Exception("MEGA failed while trying to ${operation.label}", cause)
