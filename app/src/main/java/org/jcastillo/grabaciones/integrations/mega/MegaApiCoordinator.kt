package org.jcastillo.grabaciones.integrations.mega

import android.content.Context
import android.os.Build
import java.io.File
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import nz.mega.sdk.MegaApiAndroid
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaRequest
import nz.mega.sdk.MegaRequestListenerInterface
import org.jcastillo.grabaciones.BuildConfig
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class MegaAuthenticationRequired(message: String) : Exception(message)

/** Serializes access to the SDK instance across UI authentication and background transfers. */
internal object MegaApiCoordinator {
    private const val REQUEST_TIMEOUT_MS = 120_000L
    private const val MAIN_FOLDER = "GrabacionesLocales"
    private const val USER_AGENT = "GrabacionesLocales/${BuildConfig.VERSION_NAME}"

    private val operationMutex = Mutex()
    @Volatile private var apiInstance: MegaApiAndroid? = null
    @Volatile private var activeSession: String? = null

    suspend fun signIn(context: Context, email: String, password: String, pin: String? = null): String =
        operationMutex.withLock {
            val api = getApi(context)
            if (api.isLoggedIn() > 0) {
                runCatching { awaitRequest(api, MegaRequest.TYPE_LOGOUT, MegaOperation.LOGOUT) { api.localLogout(it) } }
                activeSession = null
            }
            try {
                awaitRequest(api, MegaRequest.TYPE_LOGIN, MegaOperation.LOGIN) { listener ->
                    if (pin.isNullOrBlank()) api.login(email.trim(), password, listener)
                    else api.multiFactorAuthLogin(email.trim(), password, pin.trim(), listener)
                }
            } catch (error: MegaSdkException) {
                throw error
            } catch (error: TimeoutCancellationException) {
                throw MegaSdkException(
                    MegaOperation.LOGIN,
                    MegaError.LOCAL_ETIMEOUT,
                    "MEGA agotó el tiempo de espera al iniciar sesión.",
                    error,
                )
            }
            val session = try {
                api.dumpSession()?.takeIf(String::isNotBlank)
            } catch (error: Exception) {
                throw MegaSdkException(MegaOperation.SESSION_PERSISTENCE, null, error.message, error)
            } ?: throw MegaSdkException(
                MegaOperation.SESSION_PERSISTENCE,
                null,
                "El SDK no devolvió una sesión recuperable.",
            )
            val account = api.myEmail?.takeIf(String::isNotBlank) ?: email.trim()
            try {
                MegaSessionVault(context).save(session, account)
            } catch (error: Exception) {
                throw MegaSdkException(MegaOperation.SESSION_PERSISTENCE, null, error.message, error)
            }
            activeSession = session
            account
        }

    suspend fun logout(context: Context) = operationMutex.withLock {
        val api = getApi(context)
        // Remove the local secret first so no later worker can restore it, even if logout is offline.
        MegaSessionVault(context).clear()
        activeSession = null
        try {
            if (api.isLoggedIn() > 0) {
                withTimeout(10_000L) { awaitRequest(api, MegaRequest.TYPE_LOGOUT, MegaOperation.LOGOUT) { api.logout(it) } }
            }
        } catch (_: Exception) {
            // Local logout still happens below; network failure must not retain this device's session.
        } finally {
            runCatching { api.localLogout() }
        }
    }

    suspend fun <T> withAuthenticatedApi(context: Context, action: suspend (MegaApiAndroid) -> T): T =
        operationMutex.withLock {
            val api = getApi(context)
            authenticateStoredSession(context, api)
            action(api)
        }

    suspend fun fetchNodes(api: MegaApiAndroid) {
        awaitRequest(api, MegaRequest.TYPE_FETCH_NODES, MegaOperation.FETCH_NODES) { api.fetchNodes(it) }
    }

    suspend fun destinationFolder(api: MegaApiAndroid, preferences: MegaBackupPreferences): MegaNode {
        val root = api.rootNode ?: throw IllegalStateException("MEGA no devolvió la carpeta raíz de la cuenta.")
        val main = findOrCreateFolder(api, root, MAIN_FOLDER)
        return findOrCreateFolder(api, main, preferences.deviceFolderName())
    }

    private suspend fun findOrCreateFolder(api: MegaApiAndroid, parent: MegaNode, name: String): MegaNode {
        api.getChildNode(parent, name)?.let { existing ->
            if (!existing.isFolder) throw IllegalStateException("Ya existe un archivo remoto llamado '$name' donde debería estar la carpeta.")
            return existing
        }
        try {
            awaitRequest(api, MegaRequest.TYPE_CREATE_FOLDER, MegaOperation.CREATE_FOLDER) { api.createFolder(name, parent, it) }
        } catch (error: MegaSdkException) {
            // Another client may have created the folder between lookup and create.
            if (error.errorCode != MegaError.API_EEXIST) throw error
        }
        return api.getChildNode(parent, name)?.takeIf { it.isFolder }
            ?: throw IllegalStateException("No se pudo localizar la carpeta de respaldo '$name'.")
    }

    fun invalidateExpiredSession(context: Context, api: MegaApiAndroid) {
        MegaSessionVault(context).clear()
        activeSession = null
        runCatching { api.localLogout() }
    }

    private suspend fun authenticateStoredSession(context: Context, api: MegaApiAndroid) {
        val session = MegaSessionVault(context).readSession()
            ?: throw MegaAuthenticationRequired("Conecta tu cuenta MEGA para continuar con los respaldos.")
        if (api.isLoggedIn() > 0 && activeSession == session) return
        if (api.isLoggedIn() > 0) runCatching { api.localLogout() }
        try {
            awaitRequest(api, MegaRequest.TYPE_LOGIN, MegaOperation.SESSION_RESTORE) { api.fastLogin(session, it) }
            activeSession = session
        } catch (error: MegaSdkException) {
            if (error.errorCode == MegaError.API_ESID || error.errorCode == MegaError.API_EKEY) {
                invalidateExpiredSession(context, api)
                throw MegaAuthenticationRequired("La sesión MEGA caducó. Inicia sesión de nuevo.")
            }
            throw error
        } catch (error: TimeoutCancellationException) {
            throw MegaSdkException(
                MegaOperation.SESSION_RESTORE,
                MegaError.LOCAL_ETIMEOUT,
                "MEGA agotó el tiempo al restaurar la sesión.",
                error,
            )
        }
    }

    private fun getApi(context: Context): MegaApiAndroid = apiInstance ?: synchronized(this) {
        apiInstance ?: try {
            val sdkPath = File(context.applicationContext.filesDir, "mega_sdk").apply { mkdirs() }
            // The SDK's application key is optional; Java's null maps to nullptr in the JNI binding.
            MegaApiAndroid(null, USER_AGENT, "${sdkPath.absolutePath}/").also { apiInstance = it }
        } catch (error: MegaSdkException) {
            throw error
        } catch (error: Exception) {
            throw MegaSdkException(MegaOperation.INITIALIZATION, null, error.message, error)
        } catch (error: LinkageError) {
            throw MegaSdkException(MegaOperation.INITIALIZATION, null, error.message, error)
        }
    }

    private suspend fun awaitRequest(
        api: MegaApiAndroid,
        expectedType: Int,
        operation: MegaOperation,
        start: (MegaRequestListenerInterface) -> Unit,
    ) {
        try {
            withTimeout(REQUEST_TIMEOUT_MS) {
                suspendCancellableCoroutine<Unit> { continuation ->
                    val listener = object : MegaRequestListenerInterface {
                        override fun onRequestStart(api: MegaApiJava, request: MegaRequest) = Unit
                        override fun onRequestUpdate(api: MegaApiJava, request: MegaRequest) = Unit
                        override fun onRequestTemporaryError(api: MegaApiJava, request: MegaRequest, e: MegaError) = Unit

                        override fun onRequestFinish(api: MegaApiJava, request: MegaRequest, e: MegaError) {
                            if (request.type != expectedType || !continuation.isActive) return
                            val errorCode = e.getErrorCode()
                            val errorString = e.getErrorString()
                            if (errorCode == MegaError.API_OK) continuation.resume(Unit)
                            else continuation.resumeWithException(MegaSdkException(operation, errorCode, errorString))
                        }
                    }
                    try {
                        start(listener)
                    } catch (error: Exception) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(MegaSdkException(operation, null, error.message, error))
                        }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            currentCoroutineContext().ensureActive()
            throw MegaSdkException(
                operation,
                MegaError.LOCAL_ETIMEOUT,
                "El SDK agotó el tiempo de espera para esta operación.",
                error,
            )
        }
    }
}
