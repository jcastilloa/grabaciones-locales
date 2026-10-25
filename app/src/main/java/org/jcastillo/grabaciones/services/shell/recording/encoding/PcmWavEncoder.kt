/*
 * Modified fork of ShizuCallRecorder. The surrounding capture engine is derived from
 * https://github.com/kitsumed/ShizuCallRecorder and remains GPL-3.0-or-later.
 */
package org.jcastillo.grabaciones.services.shell.recording.encoding

import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/** Writes mono PCM16 as a seekable WAV intermediate for post-call MP3 encoding. */
class PcmWavEncoder(fileDescriptor: java.io.FileDescriptor, private val sampleRate: Int) {
    private val channel: FileChannel = FileOutputStream(fileDescriptor).channel
    private var dataBytes = 0L
    private var finished = false

    init {
        writeFully(header(0))
    }

    @Synchronized
    fun encode(pcm: ByteBuffer) {
        check(!finished) { "WAV encoder has already been finalized" }
        val data = pcm.slice()
        val size = data.remaining()
        require(size % 2 == 0) { "PCM16 data must contain complete samples" }
        writeFully(data)
        dataBytes += size
        require(dataBytes <= Int.MAX_VALUE - 36L) { "WAV intermediate exceeded the RIFF size limit" }
    }

    @Synchronized
    fun finish() {
        if (finished) return
        channel.force(true)
        channel.position(0)
        writeFully(header(dataBytes.toInt()))
        channel.force(true)
        finished = true
    }

    fun release() {
        channel.close()
    }

    private fun header(payloadSize: Int): ByteBuffer = ByteBuffer.allocate(44)
        .order(ByteOrder.LITTLE_ENDIAN)
        .put("RIFF".toByteArray(Charsets.US_ASCII))
        .putInt(36 + payloadSize)
        .put("WAVE".toByteArray(Charsets.US_ASCII))
        .put("fmt ".toByteArray(Charsets.US_ASCII))
        .putInt(16)
        .putShort(1)
        .putShort(1)
        .putInt(sampleRate)
        .putInt(sampleRate * 2)
        .putShort(2)
        .putShort(16)
        .put("data".toByteArray(Charsets.US_ASCII))
        .putInt(payloadSize)
        .flip() as ByteBuffer

    private fun writeFully(buffer: ByteBuffer) {
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) <= 0) throw IllegalStateException("Unable to write WAV data")
        }
    }
}
