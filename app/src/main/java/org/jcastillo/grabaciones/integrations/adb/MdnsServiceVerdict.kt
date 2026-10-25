/*
 * Adapted from CallVault's GPL-3.0-or-later ADB/mDNS integration; see THIRD_PARTY_NOTICES.md.
 */
package org.jcastillo.grabaciones.integrations.adb

/** Explains why a resolved wireless-ADB mDNS record was accepted or rejected. */
enum class MdnsServiceVerdict {
    ACCEPTED,
    HOST_UNRESOLVED,
    HOST_NOT_ON_THIS_DEVICE;

    companion object {
        fun of(hostAddress: String?, isOneOfOurAddresses: () -> Boolean): MdnsServiceVerdict = when {
            hostAddress.isNullOrBlank() -> HOST_UNRESOLVED
            !isOneOfOurAddresses() -> HOST_NOT_ON_THIS_DEVICE
            else -> ACCEPTED
        }
    }
}
