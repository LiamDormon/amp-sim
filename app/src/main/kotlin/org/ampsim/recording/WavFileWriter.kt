package org.ampsim.recording

import java.io.File
import java.io.RandomAccessFile

/**
 * Streaming mono 16-bit PCM WAV writer.
 *
 * Unlike `javax.sound.sampled.AudioSystem.write`, which needs the total
 * frame count known upfront to build its header, this writes a placeholder
 * header immediately, appends samples as they arrive, and re-patches the
 * RIFF/data chunk sizes in place - suited to a recording of unknown final
 * length. [patchHeader] can be called repeatedly while writing (not just on
 * [close]) so a crash mid-recording still leaves a header that correctly
 * describes however much data actually made it to disk, instead of a file
 * that claims zero length.
 *
 * All I/O here is blocking - callers must only use this from a background
 * (non-real-time, non-GTK-main-thread) dispatcher.
 */
class WavFileWriter(file: File, private val sampleRate: Int) {

    private val raf = RandomAccessFile(file, "rw")
    private var dataBytesWritten: Long = 0
    private var closed = false

    init {
        raf.setLength(0)
        writeHeaderPlaceholder()
    }

    /**
     * Convert [count] samples of [samples] (float, expected in `[-1, 1]`) to
     * 16-bit little-endian PCM and append them to the file.
     */
    fun writeSamples(samples: FloatArray, count: Int) {
        check(!closed) { "WavFileWriter is closed" }
        val bytes = ByteArray(count * BYTES_PER_SAMPLE)
        for (i in 0 until count) {
            val pcm = (samples[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort()
            bytes[i * 2] = (pcm.toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((pcm.toInt() shr 8) and 0xFF).toByte()
        }
        raf.write(bytes)
        dataBytesWritten += bytes.size
    }

    /**
     * Re-patch the RIFF chunk size and data chunk size to reflect
     * [dataBytesWritten] so far, without closing the file. Cheap (two seeks
     * plus 8 bytes) - safe to call frequently from a drain loop.
     */
    fun patchHeader() {
        val riffChunkSize = 36 + dataBytesWritten
        raf.seek(4)
        raf.write(leBytes32(riffChunkSize.toInt()))
        raf.seek(40)
        raf.write(leBytes32(dataBytesWritten.toInt()))
        raf.seek(raf.length())
    }

    /** Finalize the header and close the underlying file. Idempotent. */
    fun close() {
        if (closed) return
        patchHeader()
        raf.close()
        closed = true
    }

    private fun writeHeaderPlaceholder() {
        raf.writeBytes("RIFF")
        raf.write(leBytes32(0)) // ChunkSize, patched later
        raf.writeBytes("WAVE")
        raf.writeBytes("fmt ")
        raf.write(leBytes32(16)) // Subchunk1Size (PCM)
        raf.write(leBytes16(1)) // AudioFormat = PCM
        raf.write(leBytes16(CHANNELS))
        raf.write(leBytes32(sampleRate))
        raf.write(leBytes32(sampleRate * CHANNELS * BYTES_PER_SAMPLE)) // ByteRate
        raf.write(leBytes16(CHANNELS * BYTES_PER_SAMPLE)) // BlockAlign
        raf.write(leBytes16(BITS_PER_SAMPLE))
        raf.writeBytes("data")
        raf.write(leBytes32(0)) // Subchunk2Size, patched later
    }

    private fun leBytes16(value: Int): ByteArray =
        byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte())

    private fun leBytes32(value: Int): ByteArray = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    )

    companion object {
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
        private const val BYTES_PER_SAMPLE = BITS_PER_SAMPLE / 8
    }
}
