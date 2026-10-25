package org.jcastillo.grabaciones.audio

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.jcastillo.grabaciones.data.call.CallDirection
import org.jcastillo.grabaciones.data.call.EnrichedCallData
import org.jcastillo.grabaciones.data.recording.LocalRecordingRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class LameMp3EncoderTest {
    @Test
    fun encodesMono16KhzWavAs64KbpsMpeg2Layer3() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File(context.cacheDir, "lame-test.wav")
        val mp3 = File(context.cacheDir, "lame-test.mp3")
        writeTestWav(wav)

        try {
            val written = LameMp3Encoder().encodeWavToMp3(wav.absolutePath, mp3.absolutePath, 64)
            assertTrue("LAME should write encoded audio", written > 128)
            assertEquals(written, mp3.length())

            val header = mp3.inputStream().use { input ->
                ByteArray(4).also { bytes -> assertEquals(4, input.read(bytes)) }
            }
            val b1 = header[1].toInt() and 0xff
            val b2 = header[2].toInt() and 0xff
            val b3 = header[3].toInt() and 0xff
            assertEquals(0xff, header[0].toInt() and 0xff)
            assertEquals("MPEG-2", 2, (b1 ushr 3) and 0x03)
            assertEquals("Layer III", 1, (b1 ushr 1) and 0x03)
            assertEquals("64 kbit/s MPEG-2 Layer III index", 8, (b2 ushr 4) and 0x0f)
            assertEquals("16 kHz MPEG-2 sample rate index", 2, (b2 ushr 2) and 0x03)
            assertEquals("mono channel mode", 3, (b3 ushr 6) and 0x03)
        } finally {
            wav.delete()
            mp3.delete()
        }
    }

    @Test
    fun publishesCompletedMp3ToMediaStoreAndRemovesWavIntermediate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val repository = LocalRecordingRepository(context)
        val testNumber = "9${System.currentTimeMillis()}"
        val wav = repository.createPendingWav(
            EnrichedCallData(normalisedPhoneNumber = testNumber, direction = CallDirection.INCOMING)
        )
        writeTestWav(wav)

        try {
            val uri = repository.finalizeWav(wav)
            assertNotNull("A valid WAV should be published as MP3", uri)
            val publishedUri = checkNotNull(uri)
            assertFalse("The private WAV should be deleted only after publication", wav.exists())
            val recording = repository.listRecordings().firstOrNull { it.uri == publishedUri }
            assertNotNull("The MP3 should appear in the local history", recording)
            assertTrue(recording!!.displayName.endsWith(".mp3"))
            assertTrue(recording.sizeBytes > 128)
            context.contentResolver.query(
                publishedUri,
                arrayOf(android.provider.MediaStore.Audio.Media.RELATIVE_PATH),
                null,
                null,
                null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(LocalRecordingRepository.MUSIC_RELATIVE_PATH, cursor.getString(0))
            }
            assertTrue(repository.delete(publishedUri))
        } finally {
            wav.delete()
            repository.listRecordings()
                .filter { it.displayName.contains(testNumber) }
                .forEach { repository.delete(it.uri) }
        }
    }

    private fun writeTestWav(file: File) {
        val sampleRate = 16_000
        val samples = sampleRate * 2
        val dataBytes = samples * 2
        BufferedOutputStream(FileOutputStream(file)).use { output ->
            output.write("RIFF".toByteArray(Charsets.US_ASCII))
            output.writeLe32(36 + dataBytes)
            output.write("WAVEfmt ".toByteArray(Charsets.US_ASCII))
            output.writeLe32(16)
            output.writeLe16(1)
            output.writeLe16(1)
            output.writeLe32(sampleRate)
            output.writeLe32(sampleRate * 2)
            output.writeLe16(2)
            output.writeLe16(16)
            output.write("data".toByteArray(Charsets.US_ASCII))
            output.writeLe32(dataBytes)
            repeat(samples) { index ->
                val amplitude = (sin(2.0 * PI * 440.0 * index / sampleRate) * 12_000).toInt()
                output.write(amplitude and 0xff)
                output.write((amplitude ushr 8) and 0xff)
            }
        }
    }

    private fun java.io.OutputStream.writeLe16(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
    }

    private fun java.io.OutputStream.writeLe32(value: Int) {
        write(value and 0xff)
        write((value ushr 8) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 24) and 0xff)
    }
}
