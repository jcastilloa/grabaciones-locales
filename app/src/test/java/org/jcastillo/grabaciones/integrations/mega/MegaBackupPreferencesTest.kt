package org.jcastillo.grabaciones.integrations.mega

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MegaBackupPreferencesTest {
    @Test
    fun wifiOnlyIsOffByDefault() {
        assertFalse(MegaBackupPreferences.DEFAULT_WIFI_ONLY)
    }

    @Test
    fun destinationFolderUsesSanitizedModelAndInstallationSpecificId() {
        val first = MegaBackupPreferences.deviceFolderName("Motorola", "moto g85 5G", "a83f21")
        val second = MegaBackupPreferences.deviceFolderName("Samsung", "SM-A556B", "f70c19")

        assertTrue(first.matches(Regex("[A-Za-z0-9-]+")))
        assertTrue(first.endsWith("-a83f21"))
        assertTrue(second.endsWith("-f70c19"))
        assertNotEquals(first, second)
    }
}
