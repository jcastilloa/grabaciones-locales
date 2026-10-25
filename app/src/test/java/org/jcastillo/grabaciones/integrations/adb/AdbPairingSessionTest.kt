/* Pairing progress state shared by the foreground service and the setup screen. */
package org.jcastillo.grabaciones.integrations.adb

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbPairingSessionTest {
    @Test
    fun waitsForPersistentNotificationBeforeOpeningSettings() = runBlocking {
        AdbPairingSession.begin()
        val searching = AdbPairingSession.state.value
        assertTrue(searching.active)
        assertTrue(searching.searching)
        assertFalse(searching.notificationReady)
        assertEquals(null, searching.pairingPort)

        AdbPairingSession.notificationPublished()
        assertTrue(AdbPairingSession.state.value.notificationReady)
        AdbPairingSession.serviceFound("192.168.1.40", 37123)
        val found = AdbPairingSession.state.value
        assertTrue(found.active)
        assertFalse(found.searching)
        assertEquals("192.168.1.40", found.pairingHost)
        assertEquals(37123, found.pairingPort)
    }

    @Test
    fun successfulPairingFinishesTheSessionAndPublishesResult() = runBlocking {
        AdbPairingSession.begin()
        val sessionId = AdbPairingSession.state.value.sessionId
        AdbPairingSession.pairingSucceeded("Emparejamiento completado")
        val completed = AdbPairingSession.state.first { it.sessionId == sessionId && it.result == AdbPairingResult.SUCCESS }
        assertFalse(completed.active)
        assertNotNull(completed.message)
    }
}
