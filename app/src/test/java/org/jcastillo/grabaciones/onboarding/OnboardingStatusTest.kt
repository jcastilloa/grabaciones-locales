package org.jcastillo.grabaciones.onboarding

import org.jcastillo.grabaciones.services.callDetection.CallDetectionMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnboardingStatusTest {
    private fun status(
        batteryExempted: Boolean,
        notificationsGranted: Boolean = true,
        adbPaired: Boolean = true,
        grantedCallPermissions: Set<org.jcastillo.grabaciones.system.permissions.AppPermission> =
            CallDetectionMode.PhoneState.requiredPermissions,
    ) = OnboardingStatus.Status(
        disclaimerAccepted = true,
        notificationsGranted = notificationsGranted,
        contactsGranted = true,
        batteryExempted = batteryExempted,
        storageSelected = true,
        adbPaired = adbPaired,
        adbServiceConnected = false,
        callDetectionMode = CallDetectionMode.PhoneState,
        callDetectionModeGrantedPermissions = grantedCallPermissions,
    )

    @Test
    fun `battery exemption is recommended but does not block onboarding completion`() {
        assertTrue(status(batteryExempted = false).isComplete())
    }

    @Test
    fun `notification adb pairing and call detection remain required`() {
        assertFalse(status(batteryExempted = true, notificationsGranted = false).isComplete())
        assertFalse(status(batteryExempted = true, adbPaired = false).isComplete())
        assertFalse(status(batteryExempted = true, grantedCallPermissions = emptySet()).isComplete())
    }
}
