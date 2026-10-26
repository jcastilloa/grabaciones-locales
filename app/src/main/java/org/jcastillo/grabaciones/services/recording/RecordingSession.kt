/*
 * ShizuCallRecorder-derived code, modified for private WAV intermediates and verified MP3 output.
 * Copyright of upstream portions remains with kitsumed (Med); see LICENSE for GPL-3.0 terms.
 */
package org.jcastillo.grabaciones.services.recording

import android.app.Service
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import org.jcastillo.grabaciones.IRecordingCallback
import org.jcastillo.grabaciones.IShellService
import org.jcastillo.grabaciones.data.call.EnrichedCallData
import org.jcastillo.grabaciones.data.recording.LocalRecordingRepository
import org.jcastillo.grabaciones.data.recording.RecordingFailure
import org.jcastillo.grabaciones.data.recording.RecordingPlan
import org.jcastillo.grabaciones.integrations.mega.MegaBackupScheduler
import org.jcastillo.grabaciones.utils.AppLogger
import java.io.File

/** Owns one remote capture session and keeps its WAV until a completed MP3 has been verified. */
class RecordingSession(
    private val context: Service,
    remoteService: IShellService,
    val initializationMetadata: EnrichedCallData,
    val plan: RecordingPlan,
    private val listener: SessionListener,
) {
    interface SessionListener {
        fun onSessionFailed(failure: RecordingFailure)
    }

    class InitializationException(
        val userFriendlyMessage: String,
        technicalLogMessage: String,
        cause: Throwable? = null,
    ) : Exception(technicalLogMessage, cause)

    var currentRecordingUri: Uri? = null
        private set
    @Volatile
    var reportedFailure: RecordingFailure? = null
        private set
    var finalizationSucceeded: Boolean = false
        private set
    val hasRecoverableWav: Boolean
        get() = wavFile.isFile && wavFile.length() > 44L

    private val repository = LocalRecordingRepository(context)
    private val wavFile: File = try {
        repository.createPendingWav(initializationMetadata)
    } catch (error: Exception) {
        throw InitializationException("No se pudo crear el archivo temporal local.", "Could not create WAV intermediate", error)
    }
    private var outputPfd: ParcelFileDescriptor? = null
    private var shellService: IShellService? = remoteService
    private var released = false

    private val recordingCallback = object : IRecordingCallback.Stub() {
        override fun onRecordingFailure(error: RecordingFailure) {
            reportedFailure = error
            AppLogger.e("Remote recording engine reported failure: $error")
            // Let the service stop/finalize this session on its IO dispatcher; do not run LAME
            // conversion on a Binder callback thread.
            listener.onSessionFailed(error)
        }
    }

    init {
        try {
            outputPfd = ParcelFileDescriptor.open(
                wavFile,
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_TRUNCATE,
            )
            remoteService.startRecording(checkNotNull(outputPfd), plan, recordingCallback)
            AppLogger.i("Remote PCM capture started; the WAV is private until MP3 conversion succeeds.")
        } catch (error: RemoteException) {
            cancel()
            throw InitializationException(
                error.localizedMessage ?: "No se pudo iniciar la captura con el servicio ADB integrado.",
                "Failed to start remote recording engine",
                error,
            )
        } catch (error: Exception) {
            cancel()
            throw InitializationException(
                error.localizedMessage ?: "No se pudo iniciar la captura de audio.",
                "Could not initialize local/remote recording session",
                error,
            )
        }
    }

    fun pause() { shellService?.pauseRecording() }
    fun resume() { shellService?.resumeRecording() }

    /** Stops native capture, encodes synchronously (short local operation), and publishes the MP3. */
    @Synchronized
    fun release(): Boolean {
        if (released) return finalizationSucceeded
        released = true
        runCatching { shellService?.stopRecording() }
            .onFailure { AppLogger.e("Could not cleanly stop remote capture", it) }
        runCatching { outputPfd?.close() }
            .onFailure { AppLogger.w("Could not close WAV descriptor", it) }
        shellService = null
        outputPfd = null

        if (wavFile.length() <= 44L) {
            AppLogger.e("Recording session ended without PCM audio frames; no MP3 was created.")
            wavFile.delete()
            finalizationSucceeded = false
            return false
        }

        val result = runCatching { repository.finalizeWav(wavFile) }
            .onFailure { AppLogger.e("WAV-to-MP3 conversion failed; the source WAV was preserved", it) }
            .getOrNull()
        currentRecordingUri = result
        finalizationSucceeded = result != null
        if (result != null) {
            runCatching {
                MegaBackupScheduler.registerRecording(
                    context.applicationContext,
                    result,
                    "${wavFile.nameWithoutExtension}.mp3",
                )
            }.onFailure {
                // A cloud-queue failure never changes the already verified local recording result.
                AppLogger.e("Could not add the completed MP3 to the MEGA backup queue", it)
            }
        }
        if (!finalizationSucceeded) {
            AppLogger.e("MP3 finalization did not complete. Preserving private intermediate at ${wavFile.absolutePath}")
        }
        return finalizationSucceeded
    }

    /** Cancels a session that never successfully started; no audio is expected to be retained. */
    @Synchronized
    fun cancel() {
        if (released) return
        released = true
        runCatching { shellService?.stopRecording() }
        runCatching { outputPfd?.close() }
        shellService = null
        outputPfd = null
        wavFile.delete()
    }
}
