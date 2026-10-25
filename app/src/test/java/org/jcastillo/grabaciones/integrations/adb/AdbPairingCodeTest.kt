/* ADB pairing input validation. */
package org.jcastillo.grabaciones.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbPairingCodeTest {
    @Test
    fun acceptsExactlySixAsciiDigits() {
        assertEquals("012345", AdbPairingCode.normalize("012345"))
        assertEquals("012345", AdbPairingCode.normalize(" 01-23-45 "))
    }

    @Test
    fun rejectsShortLongAndNonAsciiCodes() {
        assertNull(AdbPairingCode.normalize("12345"))
        assertNull(AdbPairingCode.normalize("1234567"))
        assertNull(AdbPairingCode.normalize("１２３４５６"))
        assertNull(AdbPairingCode.normalize("abc123456"))
    }
}
