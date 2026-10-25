/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.jcastillo.grabaciones.services.recording

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.IntentCompat
import androidx.documentfile.provider.DocumentFile
import org.jcastillo.grabaciones.IShellService
import org.jcastillo.grabaciones.R
import org.jcastillo.grabaciones.data.AppPreferences
import org.jcastillo.grabaciones.data.call.EnrichedCallData
import org.jcastillo.grabaciones.data.recording.RecordingFailure
import org.jcastillo.grabaciones.integrations.adb.EmbeddedAdbManager
import org.jcastillo.grabaciones.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * RecordingForegroundService is the long-running service that manage the audio-recording logic.
 */
class RecordingForegroundService : Service() {
    companion object {
        // -- Intent action for controlling and initializing the service lifecycle. --

        /** Intent action sent to this service to initialize the service and immediately start a new recording session. */
        const val ACTION_START_RECORDING = "org.jcastillo.grabaciones.START_RECORDING"

        /** Intent action sent to this service to initialize and prepare the recording session in standby mode. */
        const val ACTION_STANDBY = "org.jcastillo.grabaciones.STANDBY"

        /** Intent action sent to this service to stop the current recording session and kill the service. */
        const val ACTION_STOP_RECORDING = "org.jcastillo.grabaciones.STOP_RECORDING"

        // -- Intent action for controlling an active recording session with notifications. --

        /** Intent action sent to this service to pause the current recording. */
        const val ACTION_PAUSE_RECORDING = "org.jcastillo.grabaciones.PAUSE_RECORDING"

        /** Intent action sent to this service to resume the current recording. */
        const val ACTION_RESUME_RECORDING = "org.jcastillo.grabaciones.RESUME_RECORDING"


        /**
         * Intent action sent by the standby notification's "Record" button.
         */
        const val ACTION_MANUAL_START = "org.jcastillo.grabaciones.MANUAL_START_RECORDING"

        /** Intent action sent to this service when the user dismisses the notification (Android 14+). */
        const val ACTION_NOTIFICATION_DISMISSED = "org.jcastillo.grabaciones.SERVICE_NOTIFICATION_DISMISSED"
    }

    // ── Dependencies ──────────────────────────────────────────────────────────

    private lateinit var appPreferences: AppPreferences

    private lateinit var recordingPlanResolver: RecordingPlanResolver
    private lateinit var notificationHelper: RecordingNotificationHelper

    private lateinit var overlayController: RecordingOverlayController

    /** IPC stub to the privileged ShellService running in the shell process. */
    private var shellService: IShellService? = null
    private var recordingWakeLock: PowerManager.WakeLock? = null
    private var finalizationInProgress = false

    /** Scope for service lifecycle operations (binding, etc.) */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val adbServiceLostListener: () -> Unit = {
        serviceScope.launch(Dispatchers.Main) {
            AppLogger.e("Embedded ADB shell service disconnected unexpectedly")
            if (_serviceState.value.isRecordingActive) {
                notificationHelper.showErrorNotification(getString(R.string.recording_error_adb_disconnected_unexpectedly))
                stopRecordingSessionAndService()
            } else {
                notificationHelper.showErrorNotification(getString(R.string.recording_adb_not_started))
            }
        }
    }

    // ── Recording session state ────────────────────────────────────────────────────────

    /* The current state of the service. */
    private val _serviceState = MutableStateFlow<RecordingServiceState>(RecordingServiceState.Standby(null))

    /* Exposes the current state of the recording service (read-only) for external observation. */
    val serviceState = _serviceState.asStateFlow()

    // ── Service lifecycle ──────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        notificationHelper = RecordingNotificationHelper(this)
        notificationHelper.createNotificationChannels()
        overlayController = RecordingOverlayController(this)

        appPreferences = AppPreferences(this)
        recordingPlanResolver = RecordingPlanResolver(this)

        // Launch a collector to do actions on the service state changes
        serviceScope.launch(Dispatchers.Main.immediate) { // Use immediate to ensure we get the initial (oldState) value on launch
            var oldState: RecordingServiceState = _serviceState.value
            _serviceState.collect { newState ->
                if (oldState != newState) {
                    updateNotification()
                    notificationHelper.handleStateChangeToasts(oldState, newState)
                    overlayController.showOverlay(newState)
                    oldState = newState
                }
            }
        }

        EmbeddedAdbManager.addServiceLostListener(adbServiceLostListener)

        AppLogger.d( "RecordingForegroundService initialized")
    }

    // No binding. This is a fully command-based service. All interactions are via startService with intent actions.
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Entry point for intent-based commands (START / MANUAL_START / STOP).
     *
     * Returns [Service.START_NOT_STICKY] so Android does not auto-restart the service after a process
     * kill; recording must always be explicitly triggered by an active call.
     *
     * @param intent  The intent carrying the action constant.
     * @param flags   Standard Android service start flags.
     * @param startId Unique ID for this start request (not used; state is managed manually).
     * @return [Service.START_NOT_STICKY].
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        val state = _serviceState.value

        var currentMeta = state.metadata

        // Parse metadata if present in the intent (START/STANDBY)
        if (intent != null) {
            val newMetadata = IntentCompat.getParcelableExtra(
                intent,
                EnrichedCallData.EXTRA_METADATA,
                EnrichedCallData::class.java
            )
            if (newMetadata != null) {
                currentMeta = newMetadata
            }
        }

        // Quickly show a notification to satisfy Android's foreground-service requirements;
        // wireless-ADB discovery and shell-daemon startup may take several seconds.
        updateNotification()

        when (action) {
            ACTION_START_RECORDING, ACTION_MANUAL_START -> {
                if (state.isRecordingActive) {
                    AppLogger.w( "Start request ignored. A session is already on-going.")
                    return START_NOT_STICKY
                }

                // At this point, we should already have the metadata from the intents, if it's missing, there's a logic error to be fixed.
                if (currentMeta == null) {
                    AppLogger.e( "Start request received without metadata. Cannot start recording session.")
                    notificationHelper.showErrorNotification(getString(R.string.recording_unexpected_error))
                    stopRecordingSessionAndService()
                    return START_NOT_STICKY // We won't reach this anyway.
                }

                _serviceState.update { RecordingServiceState.Starting(currentMeta) }
                acquireRecordingWakeLock()

                serviceScope.launch {
                    try {
                        val service = EmbeddedAdbManager.getShellService(this@RecordingForegroundService)
                        shellService = service
                        if (!service.canCaptureAudioOutput()) {
                            throw SecurityException("Android no concedió CAPTURE_AUDIO_OUTPUT al proceso shell.")
                        }
                        startNewRecordingSession(service, currentMeta)
                    } catch (e: Exception) {
                        // Don't convert coroutine cancellation into a user-visible service failure.
                        if (e is CancellationException) throw e
                        AppLogger.e("Embedded ADB could not provide the shell recording service", e)
                        notificationHelper.showErrorNotification(
                            getString(R.string.recording_adb_not_started) + "\n" + (e.localizedMessage ?: "")
                        )
                        stopRecordingSessionAndService()
                    } finally {
                        _serviceState.update { currentState ->
                            // If we failed to start the recording session, we should return to standby state with the current metadata.
                            if (currentState is RecordingServiceState.Starting) {
                                RecordingServiceState.Standby(currentMeta)
                            } else {
                                currentState
                            }
                        }
                    }
                }
            }

            ACTION_STANDBY -> {
                _serviceState.update { RecordingServiceState.Standby(currentMeta) }
                serviceScope.launch {
                    runCatching { EmbeddedAdbManager.getShellService(this@RecordingForegroundService) }
                        .onFailure { error ->
                            AppLogger.w("Embedded ADB service is not ready in standby", error)
                            notificationHelper.showErrorNotification(getString(R.string.recording_adb_not_started))
                        }
                    AppLogger.i("Entered standby for ${currentMeta?.direction} call")
                }
            }

            ACTION_PAUSE_RECORDING -> {
                _serviceState.update { currentState ->
                    if (currentState is RecordingServiceState.Active) {
                        currentState.session.pause() // Pause the recording session
                        currentState.copy(isPaused = true) // Update the service state to reflect the paused state
                    } else {
                        currentState
                    }
                }
            }

            ACTION_RESUME_RECORDING -> {
                _serviceState.update { currentState ->
                    if (currentState is RecordingServiceState.Active) {
                        currentState.session.resume()
                        currentState.copy(isPaused = false)
                    } else {
                        currentState
                    }
                }
            }

            ACTION_STOP_RECORDING -> stopRecordingSessionAndService()
            ACTION_NOTIFICATION_DISMISSED -> {
                AppLogger.d( "Ongoing foreground service notification dismissed by user (Android 14+), reposting.")
                updateNotification()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        // Always clean up, even if the OS kills the service mid-recording.
        // This is the guaranteed last callback before the service process is cleaned up.
        AppLogger.v( "RecordingForegroundService is destroying... Ensuring cleanup...")
        val activeSession = (_serviceState.value as? RecordingServiceState.Active)?.session
        if (activeSession != null && !finalizationInProgress) {
            val finalized = runCatching { activeSession.release() }
                .onFailure { AppLogger.e("Could not finalize recording during service destruction", it) }
                .getOrDefault(false)
            if (!finalized) showFinalizationFailure(activeSession)
        }
        serviceScope.cancel()
        overlayController.hideOverlay()
        releaseRecordingWakeLock()
        EmbeddedAdbManager.removeServiceLostListener(adbServiceLostListener)
        super.onDestroy()
    }

    // ── Service internal logic ───────────────────────────────────────

    /**
     * Starts a new recording session based on the provided parameters.
     *
     * If a session is already active, this method will log a warning and return without starting a new session.
     *
     * @param service The [IShellService] instance to use for recording.
     * @param metadata The [EnrichedCallData] containing call information for the recording session.
     */
    private fun startNewRecordingSession(service: IShellService, metadata: EnrichedCallData) {
        if (_serviceState.value.isRecordingActive) {
            AppLogger.w("startNewRecordingSession() called while a session is already active – ignoring")
            return
        }

        val plan = recordingPlanResolver.resolve(metadata)

        AppLogger.i("Resolved final recording plan: $plan")

        val activeSession = try {
            RecordingSession(
                context = this,
                remoteService = service,
                initializationMetadata = metadata,
                plan = plan,
                // Handle events reported by the remote recording engine
                listener = object : RecordingSession.SessionListener {
                    override fun onSessionFailed(failure: RecordingFailure) {
                        serviceScope.launch(Dispatchers.Main) {
                            val userFriendlyMessage = getString(failure.descriptionResId)
                            AppLogger.w("Recording session was notified of a failure with error: $failure. Showing user-friendly message: $userFriendlyMessage")
                            notificationHelper.showErrorNotification(userFriendlyMessage)
                            stopRecordingSessionAndService()
                        }
                    }
                }
            )
        } catch (e: RecordingSession.InitializationException) {
            AppLogger.e(e.message ?: "Empty Message.", "Remote recording engine reported: $e")
            notificationHelper.showErrorNotification(e.userFriendlyMessage)
            stopRecordingSessionAndService()
            return
        }

        _serviceState.update {
            RecordingServiceState.Active(
                session = activeSession,
                isPaused = false,
                metadata = metadata
            )
        }

        AppLogger.i("The recording session has been started successfully. Service state updated to Active.")
    }

    /**
     * Stops the current recording session (if any) and stops the foreground service.
     */
    private fun stopRecordingSessionAndService() {
        val activeSession = (_serviceState.value as? RecordingServiceState.Active)?.session

        if (activeSession == null) {
            AppLogger.d("No active session, exiting standby state, removing foreground notification and stopping service.")
            releaseRecordingWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        if (finalizationInProgress) {
            AppLogger.d("Recording finalization is already running; ignoring duplicate stop request.")
            return
        }

        finalizationInProgress = true
        AppLogger.i("Stopping active capture and publishing the MP3 on a background thread...")
        serviceScope.launch {
            val finalized = withContext(Dispatchers.IO) {
                runCatching { activeSession.release() }
                    .onFailure { AppLogger.e("Could not finalize the recording", it) }
                    .getOrDefault(false)
            }
            if (!finalized) showFinalizationFailure(activeSession)

            // If the user has enabled post-recording file actions, show a notification with options.
            if (appPreferences.isPostRecordingFileActionsNotificationEnabled()) {
                activeSession.currentRecordingUri?.let { fileUri ->
                    val exists = runCatching {
                        val docFile = DocumentFile.fromSingleUri(applicationContext, fileUri)
                        docFile != null && docFile.exists() && docFile.length() > 0
                    }.onFailure { AppLogger.e("Could not verify the published MP3 before showing its notification", it) }
                        .getOrDefault(false)
                    if (exists) notificationHelper.showPostCallNotification(fileUri, activeSession.initializationMetadata)
                }
            }

            finalizationInProgress = false
            releaseRecordingWakeLock()
            _serviceState.update { RecordingServiceState.Standby(null) }
            AppLogger.i("Recording session stopped and resources released. Stopping foreground service.")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun showFinalizationFailure(session: RecordingSession) {
        val outputMessage = getString(
            if (session.hasRecoverableWav) R.string.recording_failure_mp3_publish
            else R.string.recording_failure_no_audio
        )
        val captureMessage = session.reportedFailure?.let { getString(it.descriptionResId) }
        val message = listOfNotNull(captureMessage, outputMessage).joinToString("\n\n")
        AppLogger.e("Recording produced no verified public MP3: $message")
        notificationHelper.showErrorNotification(message)
    }

    private fun acquireRecordingWakeLock() {
        val powerManager = getSystemService(POWER_SERVICE) as PowerManager
        if (recordingWakeLock?.isHeld == true) return
        recordingWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:CallCapture").apply {
            setReferenceCounted(false)
            acquire(4 * 60 * 60 * 1000L)
        }
    }

    private fun releaseRecordingWakeLock() {
        recordingWakeLock?.let { lock -> if (lock.isHeld) runCatching { lock.release() } }
        recordingWakeLock = null
    }

    /**
     * Updates the foreground service notification based on the current state (Recording or Standby).
     */
    private fun updateNotification() {
        val notification = notificationHelper.getServiceNotification(_serviceState.value)
        startForegroundWithType(notification)
    }


    /**
     * Calls [startForeground] with the appropriate [ServiceInfo] foreground service type.
     *
     * @see <a href="https://developer.android.com/guide/components/foreground-services#background-start-restriction">Foreground Service Restrictions (Android 12+)</a>
     * @param notification The notification to display while in the foreground.
     */
    private fun startForegroundWithType(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) {
            // specialUse is the best type to use from Android 14+ for our call recording use-cases.
            // See: https://developer.android.com/about/versions/14/changes/fgs-types-required#special-use
            startForeground(
                RecordingNotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            // Android 11-13 uses the not yet restricted Data Sync type.
            // Starting Android 15, dataSync type is restricted to a total of 6 hours runtime in a specific time period.
            startForeground(
                RecordingNotificationHelper.SERVICE_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        }
    }
}