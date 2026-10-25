/*
 * ShizuCallRecorder: FOSS Call recording powered through ADB/Shizuku!
 *  Copyright (C) 2026-present kitsumed (Med)
 *  This software is licensed under the GNU General Public License v3 or later, with additional terms as permitted under Section 7.
 *  The full license text is available in the LICENSE file at the root of this project.
 *  This software is distributed WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 */

package org.jcastillo.grabaciones.onboarding

import android.content.Context
import org.jcastillo.grabaciones.data.AppPreferences
import org.jcastillo.grabaciones.integrations.adb.EmbeddedAdbManager
import org.jcastillo.grabaciones.services.callDetection.CallDetectionMode
import org.jcastillo.grabaciones.system.permissions.AppPermission
import org.jcastillo.grabaciones.system.permissions.PermissionChecks
import org.jcastillo.grabaciones.system.storage.SafHelper
import org.jcastillo.grabaciones.ui.viewmodels.AppNavigationViewModel

/**
 * OnboardingStatus aggregates all permission and setup states needed for the app to operate.
 *
 * Used by [AppNavigationViewModel] to decide which screen to show
 */
object OnboardingStatus {

    /**
     * An object that contains the state of every prerequisite (permissions) the app requires to work.
     *
     * @param disclaimerAccepted        True if the user has accepted the app disclaimer.
     * @param notificationsGranted      True if the app can post notifications.
     * @param contactsGranted           True if READ_CONTACTS is granted.
     * @param batteryExempted           True if the app is on the battery-optimisation whitelist.
     * @param storageSelected           True if a valid SAF recording folder has been chosen.
     * @param adbPaired                  True if this installation has completed wireless-ADB pairing.
     * @param adbServiceConnected        True if the local shell-UID recorder Binder is currently alive.
     * @param callDetectionMode         The currently selected call detection mode.
     * @param callDetectionModeGrantedPermissions True if the app has all permissions required for the current call detection mode.
     */
    data class Status(
        val disclaimerAccepted: Boolean,
        val notificationsGranted: Boolean,
        val contactsGranted: Boolean,
        val batteryExempted: Boolean,
        val storageSelected: Boolean,
        val adbPaired: Boolean,
        val adbServiceConnected: Boolean,
        val callDetectionMode: CallDetectionMode,
        val callDetectionModeGrantedPermissions: Set<AppPermission>
    ) {
        /**
         * Returns true only when every prerequisite is satisfied, including the disclaimer.
         */
        fun isComplete(): Boolean {
            // Global App Permissions
            // Battery exemption is a recommendation, not a hard prerequisite: Android/OEMs may
            // deny it, and the user must still be able to finish setup and inspect diagnostics.
            val globalsMet = disclaimerAccepted && notificationsGranted && adbPaired

            // Check if all required permissions for the current call detection mode are granted.
            val modeMet = callDetectionModeGrantedPermissions.containsAll(callDetectionMode.requiredPermissions)

            return globalsMet && modeMet
        }
    }

    /**
     * Reads the current state of permissions and other requirements and returns a [Status] data class/object.
     *
     * @param context     App context used for permission checks, SAF folder validation and other context based checks.
     * @param preferences The app-wide [AppPreferences] to perform checks based on user app settings.
     * @return A fully populated [Status] reflecting the current device state.
     */
    fun getStatus(context: Context, preferences: AppPreferences): Status {
        val storageUri = preferences.getRecordingFolderUri()
        val currentCallDetectionMode = preferences.getCallDetectionMode()

        // Make a list of all permissions required by the current call detection mode that have been granted.
        val currentModeDynamicallyGrantedList = currentCallDetectionMode.requiredPermissions.filterTo(mutableSetOf()) {
            it.isGranted(context)
        }

        return Status(
            disclaimerAccepted       = preferences.isDisclaimerAccepted(),
            notificationsGranted     = PermissionChecks.hasNotificationPermission(context),
            contactsGranted          = true, // Contact access is intentionally not requested by this minimal build.
            batteryExempted          = PermissionChecks.hasBatteryExemption(context),
            storageSelected          = true, // Final MP3 files are saved locally through MediaStore.
            adbPaired = preferences.isAdbPaired(),
            adbServiceConnected = EmbeddedAdbManager.isRecorderConnected(),
            callDetectionMode = currentCallDetectionMode,
            callDetectionModeGrantedPermissions = currentModeDynamicallyGrantedList
        )
    }
}
