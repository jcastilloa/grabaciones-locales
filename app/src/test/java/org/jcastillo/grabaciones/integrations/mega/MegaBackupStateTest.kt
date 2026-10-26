package org.jcastillo.grabaciones.integrations.mega

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MegaBackupStateTest {
    @Test
    fun failedFinalCallbackLeavesTheItemPendingAndEligibleForRetry() {
        val uploading = MegaBackupState.begin(MegaBackupStatus.PENDING)
        val afterFailure = MegaBackupState.finish(uploading, finalSdkErrorCode = -17)

        assertEquals(MegaBackupStatus.UPLOADING, uploading)
        assertEquals(MegaBackupStatus.PENDING, afterFailure)
        assertTrue(MegaBackupState.shouldEnqueue(afterFailure))
    }

    @Test
    fun onlySuccessfulFinalCallbackMakesAnUploadTerminal() {
        val uploading = MegaBackupState.begin(MegaBackupStatus.PENDING)
        val afterSuccess = MegaBackupState.finish(uploading, finalSdkErrorCode = 0)

        assertEquals(MegaBackupStatus.BACKED_UP, afterSuccess)
        assertFalse(MegaBackupState.shouldEnqueue(afterSuccess))
        assertEquals(MegaBackupStatus.BACKED_UP, MegaBackupState.begin(afterSuccess))
    }

    @Test
    fun interruptionReturnsToPendingWithoutLosingTheQueueEntry() {
        assertEquals(
            MegaBackupStatus.PENDING,
            MegaBackupState.interrupted(MegaBackupStatus.UPLOADING),
        )
        assertTrue(MegaBackupState.shouldEnqueue(MegaBackupState.interrupted(MegaBackupStatus.UPLOADING)))
    }

    @Test
    fun uncertainTransferRequiresMatchingFingerprintAsWellAsRemoteNameAndSize() {
        assertTrue(MegaBackupState.remoteFingerprintMatches(4096, 4096, "fingerprint-a", "fingerprint-a"))
        assertFalse(MegaBackupState.remoteFingerprintMatches(4096, 4096, "fingerprint-a", "fingerprint-b"))
        assertFalse(MegaBackupState.remoteFingerprintMatches(4096, 4096, "fingerprint-a", null))
        assertTrue(MegaBackupState.remoteFileMatches("call.mp3", 4096, "call.mp3", 4096, "fingerprint-a", "fingerprint-a"))
        assertFalse(MegaBackupState.remoteFileMatches("call.mp3", 4096, "other.mp3", 4096, "fingerprint-a", "fingerprint-a"))
        assertFalse(MegaBackupState.remoteFileMatches("call.mp3", 4096, "call.mp3", 4095, "fingerprint-a", "fingerprint-a"))
        assertFalse(MegaBackupState.remoteFileMatches("call.mp3", -1, "call.mp3", 4096, "fingerprint-a", "fingerprint-a"))
        assertFalse(MegaBackupState.remoteFileMatches("call.mp3", 4096, "call.mp3", 4096, "fingerprint-a", "fingerprint-b"))
        assertFalse(MegaBackupState.remoteFileMatches("call.mp3", 4096, "call.mp3", 4096, "fingerprint-a", null))
    }
}
