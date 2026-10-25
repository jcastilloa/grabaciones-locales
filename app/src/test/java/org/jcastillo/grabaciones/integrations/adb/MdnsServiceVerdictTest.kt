/*
 * ADB/mDNS checks adapted from CallVault; see THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.integrations.adb

import org.junit.Assert.assertEquals
import org.junit.Test

class MdnsServiceVerdictTest {
    @Test
    fun rejectsUnresolvedAndRemoteServicesBeforePairing() {
        assertEquals(
            MdnsServiceVerdict.HOST_UNRESOLVED,
            MdnsServiceVerdict.of(null) { error("must not inspect an unresolved address") },
        )
        assertEquals(
            MdnsServiceVerdict.HOST_NOT_ON_THIS_DEVICE,
            MdnsServiceVerdict.of("192.0.2.8") { false },
        )
    }

    @Test
    fun acceptsAResolvedAddressOnlyWhenItBelongsToThisDevice() {
        assertEquals(MdnsServiceVerdict.ACCEPTED, MdnsServiceVerdict.of("192.0.2.10") { true })
    }

    @Test
    fun usesAndroidWirelessDebuggingServiceTypes() {
        assertEquals("_adb-tls-pairing._tcp", AdbMdns.TLS_PAIRING)
        assertEquals("_adb-tls-connect._tcp", AdbMdns.TLS_CONNECT)
    }
}
