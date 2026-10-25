/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.jcastillo.grabaciones.ui.viewmodels

import android.Manifest
import android.app.Application
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import org.jcastillo.grabaciones.R
import org.jcastillo.grabaciones.data.AppPreferences
import org.jcastillo.grabaciones.integrations.adb.AdbPairingResult
import org.jcastillo.grabaciones.integrations.adb.AdbPairingSession
import org.jcastillo.grabaciones.services.adb.AdbPairingService
import org.jcastillo.grabaciones.onboarding.OnboardingStatus
import org.jcastillo.grabaciones.services.callDetection.CallDetectionMode
import org.jcastillo.grabaciones.system.openAppSettings
import org.jcastillo.grabaciones.system.permissions.PermissionChecks
import org.jcastillo.grabaciones.system.permissions.AppPermission
import org.jcastillo.grabaciones.ui.screens.PermissionsScreen
import org.jcastillo.grabaciones.utils.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The "Brain" of the permissions setup flow.
 *
 * This ViewModel decides which action to take based on the current [OnboardingStatus.Status].
 */
class PermissionsViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * Application context — safe to store in a ViewModel because it lives as long as the
     * app process, unlike an Activity context which is destroyed and recreated on every rotation.
     */
    private val appContext = application.applicationContext

    /** AppPreferences instance for accessing user preferences. */
    private val preferences = AppPreferences(appContext)


    /** MutableStateFlow to track whether a permission request is currently being processed. */
    private val _isProcessingGrantingRequest = MutableStateFlow(false)
    /** Processing state of permission request as a read-only StateFlow for UI observation. */
    val isProcessingGrantingRequest = _isProcessingGrantingRequest.asStateFlow()

    /** MutableStateFlow to hold any error messages that may occur during permission requests. */
    private val _errorMessage = MutableStateFlow<String?>(null)
    val adbPairingState = AdbPairingSession.state
    /** Read-only StateFlow for observing error messages in the UI. To be hidden when null */
    val errorMessage = _errorMessage.asStateFlow()


    /**
     * Works through each missing setup step in the correct order and invokes the matching
     * callback. Once all steps are complete, calls [onPermissionGranted] so the UI can refresh.
     *
     * For each runtime permission:
     *  - First press → the system permission dialog is shown via [requestRuntimePermission].
     *  - If the OS cannot show the popup (permanent denial), [PermissionsScreen] handles the
     *    fallback by calling [openAppSettings] in the launcher result callback.
     *
     * @param status                   Current state of every permission and setup step.
     * @param requestRuntimePermission Launches the system permission dialog for a given permission.
     * @param launchFolderPicker       Opens the folder picker to choose a recording folder.
     * @param onPermissionGranted      Called after any step completes so the UI can refresh.
     */
    fun onGrantAccess(
        status: OnboardingStatus.Status,
        requestRuntimePermission: (String) -> Unit,
        launchFolderPicker: () -> Unit,
        onStartAdbPairing: () -> Unit,
        onOpenNotificationSettings: () -> Unit,
        onPermissionGranted: () -> Unit
    ) {
        // NOTE: Don't forget to return after each action, otherwise it will call dynamic permission requests prematurely and break the flow.
        when {
            // Request notification access before pairing so the foreground-service reply action is visible.
            !status.notificationsGranted     -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                ) requestRuntimePermission(Manifest.permission.POST_NOTIFICATIONS)
                else onOpenNotificationSettings()
                return
            }
            !status.adbPaired                -> {onStartAdbPairing(); return}
            !status.contactsGranted          -> {requestRuntimePermission(Manifest.permission.READ_CONTACTS); return}
            !status.storageSelected          -> {launchFolderPicker() ; return}
            else                             -> { /* All steps completed, all global permission granted.*/ }
        }

        // Get a list of all required permissions for the current call detection mode that are not yet granted.
        val missingPermissions = status.callDetectionMode.requiredPermissions.filterNot { currentPermission ->
            status.callDetectionModeGrantedPermissions.contains(currentPermission)
        }

        if (missingPermissions.isEmpty()) {
            onPermissionGranted()
            return
        }

        when (val nextToRequest = missingPermissions.first()) {
            is AppPermission.Runtime -> {
                requestRuntimePermission(nextToRequest.manifestString)
            }
            is AppPermission.Elevated -> {
                _isProcessingGrantingRequest.value = true
                viewModelScope.launch(Dispatchers.IO) {
                    // Attempt to grant the elevated permission via the embedded ADB shell service.
                    val success = nextToRequest.grant(appContext)
                    withContext(Dispatchers.Main) {
                        _isProcessingGrantingRequest.value = false
                        if (!success) {
                            AppLogger.w( "Failed to grant elevated permission with embedded ADB.")
                            _errorMessage.value = appContext.getString(R.string.general_permission_escalation_exhausted, nextToRequest::class.simpleName, nextToRequest.permissionIdentifier, nextToRequest.escalationAttemptsChain.count())
                        }
                        // Refresh the UI to show potential changes
                        onPermissionGranted()
                    }
                }
            }
        }
        // Always trigger a refresh of the UI to detect and show new permission changes.
        onPermissionGranted()
    }

    /**
     * Starts the foreground service, waits until Android has accepted its persistent notification,
     * and only then opens Wireless debugging. The pairing code is entered exclusively through the
     * notification RemoteInput, so Settings never hides an app-owned code dialog.
     */
    fun beginAdbPairing(openWirelessDebuggingSettings: () -> Unit) {
        if (!PermissionChecks.hasNotificationPermission(appContext)) {
            AdbPairingSession.showError("Activa las notificaciones de Grabaciones Locales antes de iniciar el emparejamiento.")
            return
        }

        val sessionId = try {
            AdbPairingService.start(appContext)
        } catch (error: Exception) {
            AdbPairingSession.showError("No se pudo iniciar el emparejamiento ADB: ${error.localizedMessage ?: error.javaClass.simpleName}")
            return
        }

        viewModelScope.launch {
            val state = AdbPairingSession.state.first {
                it.sessionId == sessionId && (it.notificationReady || it.result == AdbPairingResult.FAILURE)
            }
            if (!state.notificationReady) return@launch
            delay(300)
            val latestState = AdbPairingSession.state.value
            if (latestState.sessionId != sessionId || !latestState.active || !latestState.notificationReady) return@launch
            runCatching { openWirelessDebuggingSettings() }
                .onFailure { error ->
                    AdbPairingSession.showError("La notificación está activa, pero no se pudieron abrir los ajustes: ${error.localizedMessage ?: error.javaClass.simpleName}")
                }
        }
    }

    fun cancelAdbPairing() {
        AdbPairingService.stop(appContext)
    }

    /**
     * Updates the call detection mode in preferences when the user selects a new mode.
     * @param newMode The newly selected [CallDetectionMode].
     */
    fun onCallDetectionModeChanged(newMode: CallDetectionMode) {
        preferences.setCallDetectionMode(newMode)
    }

    /**
     * Dismisses the current error message.
     */
    fun dissmissError() {
        _errorMessage.value = null
    }
}