/*
 * Copyright (C) 2026 Grabaciones Locales contributors.
 * The capture engine used by this application is derived from ShizuCallRecorder (GPL-3.0-or-later).
 */
package org.jcastillo.grabaciones.data.recording

import android.content.ContentValues
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.jcastillo.grabaciones.audio.LameMp3Encoder
import org.jcastillo.grabaciones.data.call.EnrichedCallData
import org.jcastillo.grabaciones.utils.AppLogger
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Local-only repository: private WAV intermediates and completed MP3 files in Music/GrabacionesLocales. */
class LocalRecordingRepository(private val context: Context) {
    companion object {
        const val MUSIC_FOLDER_NAME = "GrabacionesLocales"
        val MUSIC_RELATIVE_PATH = "${Environment.DIRECTORY_MUSIC}/$MUSIC_FOLDER_NAME/"
    }

    private val resolver = context.contentResolver
    private val pendingDirectory = File(context.filesDir, "pending_calls").apply { mkdirs() }

    data class Recording(
        val uri: Uri,
        val displayName: String,
        val dateAddedSeconds: Long,
        val durationMs: Long,
        val sizeBytes: Long,
    )

    fun createPendingWav(metadata: EnrichedCallData): File {
        val base = CallRecordingFileName.create(metadata.getBestNumber(), System.currentTimeMillis())
        return File(pendingDirectory, "$base.wav").also {
            check(it.createNewFile()) { "Could not create a unique private WAV intermediate" }
        }
    }

    /**
     * Encodes and publishes one WAV. The WAV is deleted only after a valid MP3 is copied,
     * published out of IS_PENDING state, and verified in MediaStore.
     */
    @Synchronized
    fun finalizeWav(wav: File): Uri? {
        if (!wav.isFile || wav.parentFile != pendingDirectory) {
            AppLogger.e("Refusing to finalize a WAV outside the private pending directory: ${wav.absolutePath}")
            return null
        }
        if (!repairAndValidateWav(wav)) {
            AppLogger.e("WAV intermediate failed validation; preserving it for recovery: ${wav.absolutePath}")
            return null
        }
        val mp3Name = wav.nameWithoutExtension + ".mp3"
        val tempMp3 = File(pendingDirectory, "$mp3Name.tmp")
        val baseName = wav.nameWithoutExtension

        try {
            findPublished(baseName)?.let { existing ->
                if (isValidMp3(existing)) {
                    wav.delete()
                    return existing
                }
            }

            tempMp3.delete()
            val encodedBytes = LameMp3Encoder().encodeWavToMp3(wav.absolutePath, tempMp3.absolutePath, 64)
            if (encodedBytes < 128 || tempMp3.length() != encodedBytes || !isValidMp3(tempMp3)) {
                tempMp3.delete()
                return null
            }

            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, mp3Name)
                put(MediaStore.Audio.Media.TITLE, baseName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, MUSIC_RELATIVE_PATH)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MediaStore.Audio.Media.DATE_ADDED, System.currentTimeMillis() / 1000L)
            }
            val outputUri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore refused to create the MP3 in $MUSIC_RELATIVE_PATH")
            try {
                resolver.openOutputStream(outputUri, "w")?.use { output ->
                    FileInputStream(tempMp3).use { input -> input.copyTo(output) }
                    output.flush()
                } ?: throw IllegalStateException("MediaStore did not open the MP3 output")

                val published = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                check(resolver.update(outputUri, published, null, null) > 0) { "Could not publish completed MP3" }
                check(isValidMp3(outputUri)) { "Published MP3 failed verification" }
                check(querySize(outputUri) >= encodedBytes) { "Published MP3 is truncated" }

                // Only now is the intermediate safe to remove.
                check(wav.delete()) { "MP3 is safe, but the private WAV could not be removed" }
                return outputUri
            } catch (error: Throwable) {
                resolver.delete(outputUri, null, null)
                throw error
            }
        } catch (error: Exception) {
            AppLogger.e("Could not publish verified MP3 for ${wav.name}; private WAV retained when possible", error)
            return null
        } finally {
            tempMp3.delete()
        }
    }

    fun listRecordings(): List<Recording> {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
        )
        val result = mutableListOf<Recording>()
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf(MUSIC_RELATIVE_PATH)

        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            while (cursor.moveToNext()) {
                val uri = Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(idCol).toString())
                val name = cursor.getString(nameCol) ?: "Grabación.mp3"
                val duration = cursor.getLong(durationCol).takeIf { it > 0 } ?: readDurationMs(uri)
                result += Recording(uri, name, cursor.getLong(dateCol), duration, cursor.getLong(sizeCol))
            }
        }
        return result
    }

    fun delete(uri: Uri): Boolean = resolver.delete(uri, null, null) > 0

    /** Recover completed/orphaned WAV files after process death. Ignore files younger than 15 minutes. */
    fun recoverPending() {
        val cutoff = System.currentTimeMillis() - 15 * 60 * 1000L
        pendingDirectory.listFiles { file -> file.extension == "wav" && file.lastModified() < cutoff }
            ?.forEach { finalizeWav(it) }
    }

    private fun findPublished(baseName: String): Uri? {
        val projection = arrayOf(MediaStore.Audio.Media._ID)
        val selection = "${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media.RELATIVE_PATH} = ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        resolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            arrayOf("$baseName.mp3", MUSIC_RELATIVE_PATH),
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) return Uri.withAppendedPath(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0).toString())
        }
        return null
    }

    private fun isValidMp3(uri: Uri): Boolean = runCatching {
        resolver.openInputStream(uri)?.use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && validMpegFrameHeader(header)
        } ?: false
    }.getOrDefault(false)

    private fun isValidMp3(file: File): Boolean = runCatching {
        FileInputStream(file).use { input ->
            val header = ByteArray(4)
            input.read(header) == 4 && validMpegFrameHeader(header)
        } && file.length() >= 128
    }.getOrDefault(false)

    private fun validMpegFrameHeader(header: ByteArray): Boolean {
        val b0 = header[0].toInt() and 0xff
        val b1 = header[1].toInt() and 0xff
        val b2 = header[2].toInt() and 0xff
        val version = (b1 ushr 3) and 0x03
        val layer = (b1 ushr 1) and 0x03
        val bitrateIndex = (b2 ushr 4) and 0x0f
        val sampleRateIndex = (b2 ushr 2) and 0x03
        // Capture WAV is 16 kHz mono; MPEG-2 Layer III index 8 is exactly 64 kbit/s.
        return b0 == 0xff && (b1 and 0xe0) == 0xe0 && version == 2 && layer == 1 &&
            bitrateIndex == 8 && sampleRateIndex == 2
    }

    private fun querySize(uri: Uri): Long {
        resolver.query(uri, arrayOf(MediaStore.Audio.Media.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) return cursor.getLong(0)
        }
        return -1
    }

    private fun readDurationMs(uri: Uri): Long = runCatching {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
    }.getOrDefault(0L)

    /** Validate our exact PCM16 mono header; repair sizes when shutdown happened before WAV finalization. */
    private fun repairAndValidateWav(file: File): Boolean = runCatching {
        RandomAccessFile(file, "rw").use { wav ->
            if (wav.length() < 46) return false
            val header = ByteArray(44)
            wav.readFully(header)
            if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
                String(header, 8, 4, Charsets.US_ASCII) != "WAVE" ||
                String(header, 12, 4, Charsets.US_ASCII) != "fmt " ||
                String(header, 36, 4, Charsets.US_ASCII) != "data" ||
                readLe16(header, 20) != 1 || readLe16(header, 22) != 1 ||
                readLe32(header, 24) != 16_000 || readLe32(header, 28) != 32_000 ||
                readLe16(header, 34) != 16) return false
            val payload = (wav.length() - 44).toInt() and -2
            if (payload <= 0) return false
            wav.seek(4)
            wav.writeLe32(36 + payload)
            wav.seek(40)
            wav.writeLe32(payload)
            wav.fd.sync()
            true
        }
    }.getOrDefault(false)

    private fun readLe16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun readLe32(bytes: ByteArray, offset: Int): Int =
        readLe16(bytes, offset) or (readLe16(bytes, offset + 2) shl 16)

    private fun RandomAccessFile.writeLe32(value: Int) {
        write(byteArrayOf(value.toByte(), (value ushr 8).toByte(), (value ushr 16).toByte(), (value ushr 24).toByte()))
    }
}

/** Stable sortable filename containing local date/time and a sanitized telephone number. */
object CallRecordingFileName {
    private val formatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.ROOT)

    fun create(phoneNumber: String, timestampMillis: Long): String {
        val safeNumber = phoneNumber.filter { it.isDigit() || it == '+' }
            .let { filtered -> if (filtered.startsWith('+')) "+${filtered.drop(1).replace("+", "")}" else filtered.replace("+", "") }
            .take(32)
            .ifBlank { "desconocido" }
        val localDateTime = Instant.ofEpochMilli(timestampMillis).atZone(ZoneId.systemDefault())
        return "${formatter.format(localDateTime)}_$safeNumber"
    }
}
