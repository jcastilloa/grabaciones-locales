package org.jcastillo.grabaciones.integrations.mega

/** Durable states used by the local backup queue. Only a final SDK success is terminal. */
enum class MegaBackupStatus {
    PENDING,
    UPLOADING,
    BACKED_UP,
}

object MegaBackupState {
    fun begin(status: MegaBackupStatus): MegaBackupStatus =
        if (status == MegaBackupStatus.BACKED_UP) status else MegaBackupStatus.UPLOADING

    fun finish(status: MegaBackupStatus, finalSdkErrorCode: Int): MegaBackupStatus =
        if (status == MegaBackupStatus.BACKED_UP || finalSdkErrorCode == 0) {
            MegaBackupStatus.BACKED_UP
        } else {
            MegaBackupStatus.PENDING
        }

    fun interrupted(status: MegaBackupStatus): MegaBackupStatus =
        if (status == MegaBackupStatus.BACKED_UP) status else MegaBackupStatus.PENDING

    fun shouldEnqueue(status: MegaBackupStatus): Boolean = status != MegaBackupStatus.BACKED_UP

    /** The SDK fingerprint includes both file contents and modification time. */
    fun remoteFingerprintMatches(
        localSize: Long,
        remoteSize: Long,
        localFingerprint: String?,
        remoteFingerprint: String?,
    ): Boolean =
        localSize >= 0 && remoteSize == localSize &&
            !localFingerprint.isNullOrBlank() && localFingerprint == remoteFingerprint

    /** Filename alone is never sufficient to reconcile an uncertain previous transfer. */
    fun remoteFileMatches(
        localName: String,
        localSize: Long,
        remoteName: String?,
        remoteSize: Long,
        localFingerprint: String?,
        remoteFingerprint: String?,
    ): Boolean =
        remoteName == localName &&
            remoteFingerprintMatches(localSize, remoteSize, localFingerprint, remoteFingerprint)
}
