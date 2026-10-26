package org.jcastillo.grabaciones.integrations.mega

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import nz.mega.sdk.MegaApiAndroid
import nz.mega.sdk.MegaApiJava
import nz.mega.sdk.MegaCancelToken
import nz.mega.sdk.MegaError
import nz.mega.sdk.MegaNode
import nz.mega.sdk.MegaTransfer
import nz.mega.sdk.MegaTransferListenerInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class MegaUploadWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    @Volatile private var activeCancelToken: MegaCancelToken? = null

    override suspend fun doWork(): Result {
        val id = MegaBackupScheduler.recordIdFrom(inputData) ?: return Result.failure()
        val database = MegaBackupDatabase(applicationContext)
        val record = database.get(id) ?: return Result.success()
        if (record.status == MegaBackupStatus.BACKED_UP) return Result.success()

        val settings = MegaBackupPreferences(applicationContext)
        if (!settings.isEnabled() || !MegaSessionVault(applicationContext).hasSession()) {
            database.markPending(id)
            return Result.success()
        }
        if (settings.isWifiOnly() && !isOnWifi()) {
            database.markPending(id, "Esperando una conexión Wi-Fi para respaldar esta grabación.")
            return Result.retry()
        }

        return try {
            setForeground(createForegroundInfo())
            MegaApiCoordinator.withAuthenticatedApi(applicationContext) { api ->
                try {
                    MegaApiCoordinator.fetchNodes(api)
                    val destination = MegaApiCoordinator.destinationFolder(api, settings)
                    val localCopy = stageLocalRecording(record.id, record.sourceUri, record.sizeBytes, record.createdAt)
                    try {
                        if (!settings.isEnabled() || (settings.isWifiOnly() && !isOnWifi())) {
                            database.markPending(id)
                            return@withAuthenticatedApi
                        }
                        val localFingerprint = api.getFingerprint(localCopy.absolutePath)
                            ?: throw IllegalStateException("No se pudo verificar el MP3 local antes de subirlo.")
                        val fingerprintMatch = api.getNodeByFingerprint(localFingerprint, destination)
                        if (fingerprintMatch != null &&
                            fingerprintMatch.parentHandle == destination.handle &&
                            !fingerprintMatch.isFolder &&
                            MegaBackupState.remoteFingerprintMatches(
                                localCopy.length(),
                                fingerprintMatch.size,
                                localFingerprint,
                                fingerprintMatch.fingerprint,
                            )
                        ) {
                            // fetchNodes completed with API_OK and MEGA found this exact file fingerprint
                            // inside this installation's destination, even if its remote name changed.
                            database.markBackedUp(id, finalSdkErrorCode = MegaError.API_OK)
                            localCopy.delete()
                            return@withAuthenticatedApi
                        }
                        val existing = api.getChildNode(destination, record.fileName)
                        if (existing != null && MegaBackupState.remoteFileMatches(
                                record.fileName,
                                localCopy.length(),
                                existing.name,
                                existing.size,
                                localFingerprint,
                                existing.fingerprint,
                            )
                        ) {
                            // Recover an uncertain transfer only after fetchNodes API_OK and a matching fingerprint.
                            database.markBackedUp(id, finalSdkErrorCode = MegaError.API_OK)
                            localCopy.delete()
                            return@withAuthenticatedApi
                        }

                        val remoteName = if (existing == null) record.fileName else uniqueConflictName(
                            api, destination, record.fileName, localCopy, localFingerprint,
                        )
                        val collision = api.getChildNode(destination, remoteName)
                        if (collision != null && MegaBackupState.remoteFileMatches(
                                remoteName,
                                localCopy.length(),
                                collision.name,
                                collision.size,
                                localFingerprint,
                                collision.fingerprint,
                            )
                        ) {
                            database.markBackedUp(id, finalSdkErrorCode = MegaError.API_OK)
                            localCopy.delete()
                            return@withAuthenticatedApi
                        }
                        database.markUploading(id)
                        uploadAndAwaitFinalCallback(api, destination, remoteName, id, localCopy)
                        // Mark complete only after the SDK's final onTransferFinish callback returned API_OK.
                        database.markBackedUp(id, finalSdkErrorCode = MegaError.API_OK)
                        localCopy.delete()
                    } finally {
                        // Keep the private staging copy after cancellation because the SDK could still
                        // be reading it. A later retry reuses it; local MediaStore MP3s are never removed.
                        if (!isStopped) localCopy.delete()
                    }
                } catch (error: MegaSdkException) {
                    if (error.errorCode == MegaError.API_ESID || error.errorCode == MegaError.API_EKEY) {
                        MegaApiCoordinator.invalidateExpiredSession(applicationContext, api)
                        throw MegaAuthenticationRequired("La sesión MEGA caducó. Inicia sesión de nuevo desde Ajustes.")
                    }
                    throw error
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            activeCancelToken?.cancel()
            activeCancelToken = null
            database.markPending(id, "La subida se interrumpió; se reintentará cuando sea posible.")
            throw cancelled
        } catch (required: MegaAuthenticationRequired) {
            database.markPending(id, required.message)
            Result.success()
        } catch (error: MegaSdkException) {
            database.markPending(id, MegaErrorMessages.backup(error))
            // WorkManager applies exponential backoff (30 seconds up to its platform cap).
            Result.retry()
        } catch (error: Exception) {
            database.markPending(id, readableGeneralError(error))
            Result.retry()
        }
    }

    private fun stageLocalRecording(id: String, uri: android.net.Uri, expectedSize: Long, stableMtime: Long): File {
        val directory = File(applicationContext.filesDir, "mega_upload_staging").apply { mkdirs() }
        val target = File(directory, "$id.mp3")
        if (target.isFile && target.length() > 0 && (expectedSize <= 0 || target.length() == expectedSize)) {
            check(target.setLastModified(stableMtime)) { "Could not set a stable MEGA fingerprint time" }
            return target
        }
        target.delete()
        val input = applicationContext.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("No se puede abrir el MP3 local; la grabación no se ha eliminado.")
        input.use { source ->
            FileOutputStream(target).use { output ->
                source.copyTo(output)
                output.flush()
                output.fd.sync()
            }
        }
        if (!target.isFile || target.length() <= 0 || (expectedSize > 0 && target.length() != expectedSize)) {
            target.delete()
            throw IllegalStateException("El MP3 local no se pudo copiar completo para el respaldo.")
        }
        check(target.setLastModified(stableMtime)) { "Could not set a stable MEGA fingerprint time" }
        return target
    }

    private suspend fun uploadAndAwaitFinalCallback(
        api: MegaApiAndroid,
        destination: MegaNode,
        remoteName: String,
        recordId: String,
        localCopy: File,
    ) {
        withTimeout(30 * 60 * 1000L) {
            suspendCancellableCoroutine { continuation ->
                val token = MegaCancelToken.createInstance()
                    ?: run {
                        continuation.resumeWithException(IllegalStateException("MEGA no pudo iniciar la transferencia."))
                        return@suspendCancellableCoroutine
                    }
                activeCancelToken = token
                continuation.invokeOnCancellation { token.cancel() }
                val listener = object : MegaTransferListenerInterface {
                    override fun onTransferStart(api: MegaApiJava, transfer: MegaTransfer) = Unit

                    override fun onTransferFinish(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) {
                        activeCancelToken = null
                        if (!continuation.isActive) return
                        if (e.errorCode == MegaError.API_OK) continuation.resume(Unit)
                        else continuation.resumeWithException(
                            MegaSdkException(MegaOperation.UPLOAD, e.getErrorCode(), e.getErrorString()),
                        )
                    }

                    override fun onTransferUpdate(api: MegaApiJava, transfer: MegaTransfer) {
                        val total = transfer.totalBytes
                        if (total > 0) {
                            val progress = (transfer.transferredBytes * 100L / total).toInt().coerceIn(0, 100)
                            setProgressAsync(Data.Builder().putInt("progress", progress).build())
                        }
                    }

                    override fun onTransferTemporaryError(api: MegaApiJava, transfer: MegaTransfer, e: MegaError) = Unit
                    override fun onTransferData(api: MegaApiJava, transfer: MegaTransfer, buffer: ByteArray): Boolean = false
                    override fun onFolderTransferUpdate(
                        api: MegaApiJava,
                        transfer: MegaTransfer,
                        stage: Int,
                        folderCount: Long,
                        createdFolderCount: Long,
                        fileCount: Long,
                        currentFolder: String?,
                        currentFileLeafName: String?,
                    ) = Unit
                }
                try {
                    // isSourceTemporary=false is essential: only the private staging copy is used,
                    // and the original local recording remains untouched in MediaStore.
                    api.startUpload(
                        localCopy.absolutePath,
                        destination,
                        remoteName,
                        localCopy.lastModified() / 1000L,
                        recordId,
                        false,
                        false,
                        token,
                        listener,
                    )
                } catch (error: Exception) {
                    activeCancelToken = null
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        }
    }

    private fun uniqueConflictName(
        api: MegaApiAndroid,
        parent: MegaNode,
        original: String,
        file: File,
        localFingerprint: String,
    ): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val digestPart = digest.digest().take(6).joinToString("") { "%02x".format(Locale.ROOT, it) }
        val dot = original.lastIndexOf('.')
        val stem = if (dot > 0) original.substring(0, dot) else original
        val extension = if (dot > 0) original.substring(dot) else ".mp3"
        var candidate = "$stem-$digestPart$extension"
        var suffix = 2
        while (true) {
            val remote = api.getChildNode(parent, candidate) ?: return candidate
            if (MegaBackupState.remoteFileMatches(
                    candidate,
                    file.length(),
                    remote.name,
                    remote.size,
                    localFingerprint,
                    remote.fingerprint,
                )
            ) return candidate
            candidate = "$stem-$digestPart-$suffix$extension"
            suffix++
        }
    }

    private fun isOnWifi(): Boolean {
        val manager = ContextCompat.getSystemService(applicationContext, ConnectivityManager::class.java) ?: return false
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private suspend fun createForegroundInfo(): ForegroundInfo {
        val manager = ContextCompat.getSystemService(applicationContext, NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager?.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Respaldo MEGA", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Estado de las subidas de grabaciones a MEGA"
                },
            )
        }
        val notification: Notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Respaldo MEGA")
            .setContentText("Subiendo una grabación")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun readableGeneralError(error: Exception): String = when (error) {
        is TimeoutCancellationException -> "La transferencia tardó demasiado. Se reintentará automáticamente."
        else -> when {
            error.message?.contains("App Key", ignoreCase = true) == true ->
                "MEGA rechazó la configuración de la solicitud a la API."
            error.message?.contains("no respondió", ignoreCase = true) == true ->
                "MEGA no respondió. Comprueba la conexión; se reintentará automáticamente."
            error.message?.startsWith("No se puede") == true || error.message?.startsWith("El MP3") == true -> error.message.orEmpty()
            else -> "No se pudo completar el respaldo. Se conservará la grabación y se reintentará."
        }
    }

    private companion object {
        const val CHANNEL_ID = "mega_backup"
        const val NOTIFICATION_ID = 4207
    }
}
