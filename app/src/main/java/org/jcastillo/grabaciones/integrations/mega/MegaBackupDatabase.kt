package org.jcastillo.grabaciones.integrations.mega

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import java.util.UUID

internal data class MegaBackupRecord(
    val id: String,
    val sourceUri: Uri,
    val fileName: String,
    val sizeBytes: Long,
    val status: MegaBackupStatus,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Long,
    val updatedAt: Long,
)

/** A persistent outbox. The source recording stays in MediaStore and is never deleted here. */
internal class MegaBackupDatabase(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    @Synchronized
    fun register(uri: Uri, fileName: String, sizeBytes: Long): MegaBackupRecord {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_ID, UUID.randomUUID().toString())
            put(COL_URI, uri.toString())
            put(COL_NAME, fileName)
            put(COL_SIZE, sizeBytes)
            put(COL_STATUS, MegaBackupStatus.PENDING.name)
            put(COL_ATTEMPTS, 0)
            put(COL_CREATED, System.currentTimeMillis())
            put(COL_UPDATED, System.currentTimeMillis())
        }
        db.insertWithOnConflict(TABLE, null, values, SQLiteDatabase.CONFLICT_IGNORE)
        return getByUri(uri) ?: error("Could not persist MEGA backup queue entry")
    }

    @Synchronized
    fun get(id: String): MegaBackupRecord? = readableDatabase.query(
        TABLE, COLUMNS, "$COL_ID = ?", arrayOf(id), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    @Synchronized
    fun pending(): List<MegaBackupRecord> = queryRecords(
        "$COL_STATUS <> ?",
        arrayOf(MegaBackupStatus.BACKED_UP.name),
        "$COL_CREATED ASC",
    )

    @Synchronized
    fun recent(limit: Int = 6): List<MegaBackupRecord> = queryRecords(
        null, null, "$COL_UPDATED DESC", limit.toString(),
    )

    @Synchronized
    fun pendingCount(): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM $TABLE WHERE $COL_STATUS <> ?",
        arrayOf(MegaBackupStatus.BACKED_UP.name),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

    @Synchronized
    fun markUploading(id: String) {
        val current = get(id) ?: return
        if (current.status == MegaBackupStatus.BACKED_UP) return
        val values = ContentValues().apply {
            put(COL_STATUS, MegaBackupState.begin(current.status).name)
            put(COL_ATTEMPTS, current.attempts + 1)
            putNull(COL_ERROR)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE,
            values,
            "$COL_ID = ? AND $COL_STATUS <> ?",
            arrayOf(id, MegaBackupStatus.BACKED_UP.name),
        )
    }

    @Synchronized
    fun markPending(id: String, error: String? = null) {
        val values = ContentValues().apply {
            put(COL_STATUS, MegaBackupState.interrupted(MegaBackupStatus.UPLOADING).name)
            if (error.isNullOrBlank()) putNull(COL_ERROR) else put(COL_ERROR, error.take(MAX_ERROR_LENGTH))
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "$COL_ID = ? AND $COL_STATUS <> ?", arrayOf(id, MegaBackupStatus.BACKED_UP.name))
    }

    @Synchronized
    fun markBackedUp(id: String, finalSdkErrorCode: Int) {
        val current = get(id) ?: return
        val next = MegaBackupState.finish(current.status, finalSdkErrorCode)
        val values = ContentValues().apply {
            put(COL_STATUS, next.name)
            putNull(COL_ERROR)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.update(TABLE, values, "$COL_ID = ?", arrayOf(id))
    }

    @Synchronized
    fun resetInterrupted() {
        val values = ContentValues().apply {
            put(COL_STATUS, MegaBackupStatus.PENDING.name)
            put(COL_UPDATED, System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE,
            values,
            "$COL_STATUS = ?",
            arrayOf(MegaBackupStatus.UPLOADING.name),
        )
    }

    private fun getByUri(uri: Uri): MegaBackupRecord? = readableDatabase.query(
        TABLE, COLUMNS, "$COL_URI = ?", arrayOf(uri.toString()), null, null, null, "1",
    ).use { cursor -> if (cursor.moveToFirst()) cursor.toRecord() else null }

    private fun queryRecords(selection: String?, args: Array<String>?, order: String, limit: String? = null): List<MegaBackupRecord> {
        val records = mutableListOf<MegaBackupRecord>()
        readableDatabase.query(TABLE, COLUMNS, selection, args, null, null, order, limit).use { cursor ->
            while (cursor.moveToNext()) records += cursor.toRecord()
        }
        return records
    }

    private fun Cursor.toRecord() = MegaBackupRecord(
        id = getString(getColumnIndexOrThrow(COL_ID)),
        sourceUri = Uri.parse(getString(getColumnIndexOrThrow(COL_URI))),
        fileName = getString(getColumnIndexOrThrow(COL_NAME)),
        sizeBytes = getLong(getColumnIndexOrThrow(COL_SIZE)),
        status = runCatching { MegaBackupStatus.valueOf(getString(getColumnIndexOrThrow(COL_STATUS))) }
            .getOrDefault(MegaBackupStatus.PENDING),
        attempts = getInt(getColumnIndexOrThrow(COL_ATTEMPTS)),
        lastError = getString(getColumnIndexOrThrow(COL_ERROR)),
        createdAt = getLong(getColumnIndexOrThrow(COL_CREATED)),
        updatedAt = getLong(getColumnIndexOrThrow(COL_UPDATED)),
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE $TABLE (
                $COL_ID TEXT PRIMARY KEY NOT NULL,
                $COL_URI TEXT NOT NULL UNIQUE,
                $COL_NAME TEXT NOT NULL,
                $COL_SIZE INTEGER NOT NULL,
                $COL_STATUS TEXT NOT NULL,
                $COL_ATTEMPTS INTEGER NOT NULL DEFAULT 0,
                $COL_ERROR TEXT,
                $COL_CREATED INTEGER NOT NULL,
                $COL_UPDATED INTEGER NOT NULL
            )""".trimIndent(),
        )
        db.execSQL("CREATE INDEX mega_backup_status_updated ON $TABLE ($COL_STATUS, $COL_UPDATED)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep this table additive; the initial schema is intentionally small and versioned.
    }

    private companion object {
        const val DATABASE_NAME = "mega_backup_queue.db"
        const val DATABASE_VERSION = 1
        const val TABLE = "backup_jobs"
        const val COL_ID = "id"
        const val COL_URI = "source_uri"
        const val COL_NAME = "file_name"
        const val COL_SIZE = "size_bytes"
        const val COL_STATUS = "status"
        const val COL_ATTEMPTS = "attempts"
        const val COL_ERROR = "last_error"
        const val COL_CREATED = "created_at"
        const val COL_UPDATED = "updated_at"
        const val MAX_ERROR_LENGTH = 240
        val COLUMNS = arrayOf(COL_ID, COL_URI, COL_NAME, COL_SIZE, COL_STATUS, COL_ATTEMPTS, COL_ERROR, COL_CREATED, COL_UPDATED)
    }
}
