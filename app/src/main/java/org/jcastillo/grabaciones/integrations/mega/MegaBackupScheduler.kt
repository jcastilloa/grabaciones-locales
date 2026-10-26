package org.jcastillo.grabaciones.integrations.mega

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/** Registers only MP3s produced after this feature is installed; never scans recording history. */
object MegaBackupScheduler {
    const val WORK_TAG = "mega-mp3-backup"
    private const val INPUT_RECORD_ID = "backup_record_id"
    private const val UNIQUE_WORK_PREFIX = "mega-mp3-"

    fun registerRecording(context: Context, uri: android.net.Uri, fallbackName: String) {
        val appContext = context.applicationContext
        val (name, size) = runCatching {
            appContext.contentResolver.query(
                uri,
                arrayOf(MediaStore.Audio.Media.DISPLAY_NAME, MediaStore.Audio.Media.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    (cursor.getString(0)?.takeIf(String::isNotBlank) ?: fallbackName) to cursor.getLong(1)
                } else fallbackName to -1L
            } ?: (fallbackName to -1L)
        }.getOrDefault(fallbackName to -1L)

        val record = MegaBackupDatabase(appContext).register(uri, name, size)
        scheduleIfReady(appContext, record, ExistingWorkPolicy.KEEP)
    }

    fun schedulePending(context: Context, appendAfterExisting: Boolean = false): Int {
        val appContext = context.applicationContext
        val db = MegaBackupDatabase(appContext)
        db.resetInterrupted()
        if (!isReady(appContext)) return 0
        val policy = if (appendAfterExisting) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
        val records = db.pending()
        records.forEach { schedule(appContext, it, policy) }
        return records.size
    }

    private fun scheduleIfReady(context: Context, record: MegaBackupRecord, policy: ExistingWorkPolicy) {
        if (isReady(context) && MegaBackupState.shouldEnqueue(record.status)) schedule(context, record, policy)
    }

    private fun isReady(context: Context): Boolean =
        MegaBackupPreferences(context).isEnabled() && MegaSessionVault(context).hasSession()

    private fun schedule(context: Context, record: MegaBackupRecord, policy: ExistingWorkPolicy) {
        // The worker enforces the current Wi-Fi preference itself. A stable CONNECTED constraint
        // lets preference changes take effect for already-enqueued work without replacing a running upload.
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<MegaUploadWorker>()
            .setInputData(Data.Builder().putString(INPUT_RECORD_ID, record.id).build())
            .setConstraints(constraints)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .addTag(WORK_TAG)
            .addTag("$WORK_TAG-${record.id}")
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork("$UNIQUE_WORK_PREFIX${record.id}", policy, request)
    }

    internal fun recordIdFrom(inputData: Data): String? = inputData.getString(INPUT_RECORD_ID)
}
